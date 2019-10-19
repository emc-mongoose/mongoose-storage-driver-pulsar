package com.emc.mongoose.storage.driver.pulsar;

import com.emc.mongoose.base.config.IllegalConfigurationException;
import com.emc.mongoose.base.data.DataInput;
import com.emc.mongoose.base.item.DataItem;
import com.emc.mongoose.base.item.ItemFactory;
import com.emc.mongoose.base.item.op.OpType;
import static com.emc.mongoose.base.item.op.Operation.Status;
import com.emc.mongoose.base.item.op.data.DataOperation;
import com.emc.mongoose.base.logging.LogUtil;
import com.emc.mongoose.base.storage.Credential;
import com.emc.mongoose.storage.driver.coop.CoopStorageDriverBase;
import com.emc.mongoose.storage.driver.pulsar.cache.ProducerFunction;
import com.github.akurilov.confuse.Config;
import lombok.Value;
import lombok.val;
import org.apache.logging.log4j.Level;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static com.emc.mongoose.base.item.op.Operation.Status.FAIL_IO;
import static com.emc.mongoose.base.item.op.Operation.Status.FAIL_UNKNOWN;
import static com.emc.mongoose.base.item.op.Operation.Status.SUCC;
import static com.emc.mongoose.storage.driver.pulsar.Constants.MAX_MSG_SIZE;
import static com.github.akurilov.commons.lang.Exceptions.throwUnchecked;

public class PulsarStorageDriver<I extends DataItem, O extends DataOperation<I>>
extends CoopStorageDriverBase<I, O> {

	protected final int storageNodePort;
	private final Map<String, PulsarClient> endpoints = new HashMap<>();
	private final Map<String, ProducerFunction> producerFunctionCache = new ConcurrentHashMap<>();
	private final ThreadLocal<Map<String, Producer<byte[]>>> producerCacheThreadLocal = ThreadLocal.withInitial(HashMap::new);

	public PulsarStorageDriver(
		final String stepId, final DataInput dataInput, final Config storageConfig, final boolean verifyFlag,
		final int batchSize
	) throws IllegalConfigurationException {
		super(stepId, dataInput, storageConfig, verifyFlag, batchSize);
		val netConfig = storageConfig.configVal("net");
		val tcpNoDelay = netConfig.boolVal("tcpNoDelay");
		val connTimeoutMillis = netConfig.intVal("timeoutMilliSec");
		val nodeConfig = netConfig.configVal("node");
		storageNodePort = nodeConfig.intVal("port");
		val t = nodeConfig.<String>listVal("addrs");
		for (val n: t) {
			val nodeAddrWithPort = n + (n.contains(":") ? "" : ":" + storageNodePort);
			try {
				val client = PulsarClient
					.builder()
					//.allowTlsInsecureConnection(true)
					//.authentication(new AuthenticationDisabled())
					.connectionsPerBroker(concurrencyLimit > 0 ? concurrencyLimit : Integer.MAX_VALUE)
					.connectionTimeout(
						connTimeoutMillis > 0 ? connTimeoutMillis : Integer.MAX_VALUE,
						TimeUnit.MILLISECONDS
					)
					.enableTcpNoDelay(tcpNoDelay)
					//.enableTlsHostnameVerification(false)
					.ioThreads(ioWorkerCount)
					//.listenerThreads(1)
					//.keepAliveInterval(1, TimeUnit.DAYS)
					//.maxConcurrentLookupRequests(1)
					.serviceUrl("pulsar://" + nodeAddrWithPort)
					//.statsInterval(1, TimeUnit.DAYS)
					.build();
			} catch(final PulsarClientException | IllegalArgumentException e) {
				throw new IllegalConfigurationException(e);
			}
		}
	}

	@Value
	final class ProducerFunctionImpl
	implements ProducerFunction {

		String nodeAddr;

		@Override
		public final Producer apply(final String topicName) {
			val client = endpoints.get(nodeAddr);
			try {
				return client
					.newProducer()
					.topic(topicName)
					.create();
			} catch(final PulsarClientException e) {
				LogUtil.exception(
					Level.ERROR, e, "{}: failed to create producer for the client \"{}\" and topic \"{}\"",
					stepId, client, topicName
				);
				throwUnchecked(e);
			}
			return null;
		}
	}

	@Override
	protected final boolean submit(final O op)
	throws IllegalStateException {
		val opType = op.type();
		switch(opType) {
			case NOOP:
				return noop(op);
			case CREATE:
				return submitMessageCreate(op);
			case READ:
				return submitMessageRead(op);
			default:
				throw new AssertionError("Unexpected operation type: " + opType);
		}
	}

	@Override
	protected final int submit(final List<O> ops, final int from, final int to)
	throws IllegalStateException {
		return 0;
	}

	@Override
	protected final int submit(final List<O> ops)
	throws IllegalStateException {
		return submit(ops, 0, ops.size());
	}

	final boolean noop(final O op) {
		op.startRequest();
		op.finishRequest();
		op.startResponse();
		try {
			op.countBytesDone(op.item().size());
		} catch (final IOException ignored) {}
		op.finishResponse();
		op.status(SUCC);
		handleCompleted(op);
		return true;
	}

	final boolean submitMessageCreate(final O op) {
		val nodeAddr = op.nodeAddr();
		val producerFunc = producerFunctionCache.computeIfAbsent(nodeAddr, ProducerFunctionImpl::new);
		val topicName = op.dstPath();
		val producerCache = producerCacheThreadLocal.get();
		val producer = producerCache.computeIfAbsent(topicName, producerFunc);
		val item = op.item();
		try {
			val msgSize = item.size();
			if(msgSize > MAX_MSG_SIZE) {
				failOperation(op, FAIL_IO);
			} else if(msgSize < 0) {
				failOperation(op, FAIL_IO);
			} else {
				if(concurrencyThrottle.tryAcquire()) {
					val msgData = ByteBuffer.allocate((int) msgSize);
					for(var n = 0L; n < msgSize; n += item.read(msgData)); // copy the data to the buffer
					op.startRequest();
					val f = producer
						.newMessage()
						.value(msgData.array())
						.sendAsync();
					try {
						op.finishRequest();
					} catch(final IllegalStateException ignored) {
					}
					f.handle((msgId, thrown) -> handleMessageCreate(op, msgSize, thrown));
				} else {
					return false;
				}
			}
		} catch(final IOException e) {
			throw new AssertionError(e);
		}
		return true;
	}

	final Object handleMessageCreate(final O op, final long msgSize, final Throwable thrown) {
		try {
			if(null == thrown) {
				op.startResponse();
				op.finishResponse();
				op.countBytesDone(msgSize);
				op.status(SUCC);
				handleCompleted(op);
			} else {
				failOperation(op, FAIL_UNKNOWN);
			}
		} finally {
			concurrencyThrottle.release();
		}
		return null;
	}

	final boolean submitMessageRead(final O op) {

	}

	final void failOperation(final O op, final Status status) {
		op.status(status);
		handleCompleted(op);
	}

	@Override
	protected final String requestNewPath(final String path) {
		throw new AssertionError("Should not be invoked");
	}

	@Override
	protected String requestNewAuthToken(final Credential credential) {
		return null;
	}

	@Override
	public List<I> list(
		final ItemFactory<I> itemFactory, final String path, final String prefix, final int idRadix,
		final I lastPrevItem, final int count
	) throws IOException {
		return null;
	}

	@Override
	public void adjustIoBuffers(final long avgTransferSize, final OpType opType) {
	}

	@Override
	protected final void doClose() {
		endpoints
			.forEach(
				(endpoint, client) -> {
					try {
						client.close();
					} catch(final PulsarClientException e) {
						LogUtil.exception(
							Level.WARN, e, "{}: failed to close the client for the endpoint: {}", stepId, endpoint
						);
					}
				}
			);
		endpoints.clear();
	}
}

package com.emc.mongoose.storage.driver.pulsar;

import com.emc.mongoose.base.config.IllegalConfigurationException;
import com.emc.mongoose.base.data.DataInput;
import com.emc.mongoose.base.item.DataItem;
import com.emc.mongoose.base.item.ItemFactory;
import com.emc.mongoose.base.item.op.OpType;

import static com.emc.mongoose.base.item.op.Operation.SLASH;
import static com.emc.mongoose.base.item.op.Operation.Status;
import com.emc.mongoose.base.item.op.data.DataOperation;
import com.emc.mongoose.base.logging.LogUtil;
import com.emc.mongoose.base.storage.Credential;
import com.emc.mongoose.storage.driver.coop.CoopStorageDriverBase;
import com.emc.mongoose.storage.driver.pulsar.cache.ConsumerFunction;
import com.emc.mongoose.storage.driver.pulsar.cache.ProducerFunction;
import com.github.akurilov.confuse.Config;
import lombok.Value;
import lombok.val;
import org.apache.logging.log4j.Level;
import org.apache.pulsar.client.api.CompressionType;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.emc.mongoose.base.item.op.Operation.Status.FAIL_IO;
import static com.emc.mongoose.base.item.op.Operation.Status.FAIL_UNKNOWN;
import static com.emc.mongoose.base.item.op.Operation.Status.SUCC;
import static com.emc.mongoose.storage.driver.pulsar.Constants.MAX_MSG_SIZE;

public class PulsarStorageDriver<I extends DataItem, O extends DataOperation<I>>
extends CoopStorageDriverBase<I, O> {

	protected final int storageNodePort;
	protected final CompressionType compressionType;
	protected final String storageNodeAddrs[];
	private final Map<String, PulsarClient> clients = new ConcurrentHashMap<>();
	private final Map<String, ProducerFunction> producerFuncCache = new ConcurrentHashMap<>();
	private final Map<String, Producer<byte[]>> producerCache = new ConcurrentHashMap<>();
	private final Map<String, ConsumerFunction> consumerFuncCache = new ConcurrentHashMap<>();
	private final Map<String, Consumer<byte[]>> consumerCache = new ConcurrentHashMap<>();

	public PulsarStorageDriver(
		final String stepId, final DataInput dataInput, final Config storageConfig, final boolean verifyFlag,
		final int batchSize
	) throws IllegalConfigurationException {
		super(stepId, dataInput, storageConfig, verifyFlag, batchSize);
		val driverConfig = storageConfig.configVal("driver");
		val msgConfig = driverConfig.configVal("message");
		val compressionRaw = (String) msgConfig.val("compression");
		try {
			compressionType = CompressionType.valueOf(compressionRaw.toUpperCase());
		} catch(final Exception e) {
			throw new IllegalConfigurationException(
				"Invalid compression type value: \"" + compressionRaw + "\", valid values are: " +
					Arrays
						.stream(CompressionType.values())
						.map(Enum::toString)
						.collect(Collectors.joining(", "))
			);
		}
		val netConfig = storageConfig.configVal("net");
		val tcpNoDelay = netConfig.boolVal("tcpNoDelay");
		val connTimeoutMillis = netConfig.intVal("timeoutMilliSec");
		val nodeConfig = netConfig.configVal("node");
		storageNodePort = nodeConfig.intVal("port");
		val t = nodeConfig.<String>listVal("addrs").toArray(new String[]{});
		storageNodeAddrs = new String[t.length];
		for (var i = 0; i < t.length; i ++) {
			val n = t[i];
			storageNodeAddrs[i] = n + (n.contains(":") ? "" : ":" + storageNodePort);
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
					.serviceUrl("pulsar://" + storageNodeAddrs[i])
					//.statsInterval(1, TimeUnit.DAYS)
					.build();
				clients.put(storageNodeAddrs[i], client);
			} catch(final PulsarClientException | IllegalArgumentException e) {
				throw new IllegalConfigurationException(e);
			}
		}
		requestAuthTokenFunc = null;
		requestNewPathFunc = null;
	}

	@Value
	final class ProducerFunctionImpl
	implements ProducerFunction {

		String nodeAddr;

		@Override
		public final Producer<byte[]> apply(final String topicName) {
			var producer = (Producer<byte[]>) null;
			try {
				producer = clients
					.get(nodeAddr)
					.newProducer()
					.topic(topicName)
					.create();
			} catch(final Exception e) {
				LogUtil.exception(
					Level.ERROR, e, "{}: failed to create a producer for the node \"{}\" and topic \"{}\"",
					stepId, nodeAddr, topicName
				);
			}
			return producer;
		}
	}

	@Value
	final class ConsumerFunctionImpl
	implements ConsumerFunction {

		String nodeAddr;

		@Override
		public final Consumer<byte[]> apply(final String topicName) {
			var consumer = (Consumer<byte[]>) null;
			try {
				consumer = clients
					.get(nodeAddr)
					.newConsumer()
					.topic(topicName)
					.subscriptionName(Long.toString(System.nanoTime()))
					.subscribe();
			} catch(final Exception e) {
				LogUtil.exception(
					Level.ERROR, e, "{}: failed to create a consumer for the node \"{}\" and topic \"{}\"",
					stepId, nodeAddr, topicName
				);
			}
			return consumer;
		}
	}

	@Override
	protected final boolean prepare(final O op) {
		if(super.prepare(op)) {
			op.nodeAddr(storageNodeAddrs[ThreadLocalRandom.current().nextInt(storageNodeAddrs.length)]);
			return true;
		}
		return false;
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
		val anyOp = ops.get(from);
		val opType = anyOp.type();
		switch(opType) {
			case NOOP:
				return noop(ops, from, to);
			case CREATE:
				return submitMessagesCreate(ops, from, to);
			case READ:
				return submitMessagesRead(ops, from, to);
			default:
				throw new AssertionError("Unexpected operation type: " + opType);
		}
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

	final int noop(final List<O> ops, final int from, final int to) {
		for(var i = from; i < to; i ++) {
			noop(ops.get(i));
		}
		return to - from;
	}

	final boolean submitMessageCreate(final O op) {
		val nodeAddr = op.nodeAddr();
		val producerFunc = producerFuncCache.computeIfAbsent(nodeAddr, ProducerFunctionImpl::new);
		val topicName = op.dstPath();
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

	final int submitMessagesCreate(final List<O> ops, final int from, final int to) {
		for(var i = from; i < to; i ++) {
			submitMessageCreate(ops.get(i));
		}
		return to - from;
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
		val nodeAddr = op.nodeAddr();
		val consumerFunc = consumerFuncCache.computeIfAbsent(nodeAddr, ConsumerFunctionImpl::new);
		val topicName = op.dstPath();
		val producer = consumerCache.computeIfAbsent(topicName, consumerFunc);
		if(concurrencyThrottle.tryAcquire()) {
			op.startRequest();
			val f = producer.receiveAsync();
			try {
				op.finishRequest();
			} catch(final IllegalStateException ignored) {
			}
			f.handle((msg, thrown) -> handleMessageRead(op, msg, thrown));
			return true;
		}
		return false;
	}

	final int submitMessagesRead(final List<O> ops, final int from, final int to) {
		for(var i = from; i < to; i ++) {
			submitMessageRead(ops.get(i));
		}
		return to - from;
	}

	final Object handleMessageRead(final O op, final Message<byte[]> msg, final Throwable thrown) {
		try {
			if(null == thrown) {
				op.startResponse();
				op.finishResponse();
				op.status(SUCC);
				if(null == msg) {
					op.countBytesDone(0);
				} else {
					val msgData = msg.getData();
					if(null == msgData) {
						op.countBytesDone(0);
					} else {
						op.countBytesDone(msgData.length);
					}
				}
				handleCompleted(op);
			} else {
				failOperation(op, FAIL_UNKNOWN);
			}
		} finally {
			concurrencyThrottle.release();
		}
		return null;
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
		if (null != lastPrevItem) {
			throw new EOFException();
		}
		val items = new ArrayList<I>();
		for (var i = 0; i < count; i++) {
			items.add(itemFactory.getItem(path + SLASH + (prefix == null ? i : prefix + i), 0, 0));
		}
		return items;
	}

	@Override
	public void adjustIoBuffers(final long avgTransferSize, final OpType opType) {
	}

	@Override
	protected final void doClose() {
		closeAll(clients.values());
		clients.clear();
		closeAll(producerCache.values());
		producerCache.clear();
		closeAll(consumerCache.values());
		consumerCache.clear();
		producerFuncCache.clear();
		consumerFuncCache.clear();
	}

	final void closeAll(final Collection<? extends AutoCloseable> closeables) {
		closeables
			.parallelStream()
			.forEach(
				entry -> {
					try {
						entry.close();
					} catch(final Exception e) {
						LogUtil.exception(Level.WARN, e, "{}: failed to close: {}", stepId, entry);
					}
				}
			);
		closeables.clear();
	}
}

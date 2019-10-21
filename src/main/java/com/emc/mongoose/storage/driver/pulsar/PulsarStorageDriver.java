package com.emc.mongoose.storage.driver.pulsar;

import com.emc.mongoose.base.config.IllegalConfigurationException;
import com.emc.mongoose.base.data.DataInput;
import com.emc.mongoose.base.item.DataItem;
import com.emc.mongoose.base.item.ItemFactory;
import com.emc.mongoose.base.item.op.OpType;
import com.emc.mongoose.base.item.op.Operation.Status;
import com.emc.mongoose.base.item.op.data.DataOperation;
import com.emc.mongoose.base.logging.LogUtil;
import com.emc.mongoose.base.logging.Loggers;
import com.emc.mongoose.base.storage.Credential;
import com.emc.mongoose.storage.driver.coop.CoopStorageDriverBase;

import static com.emc.mongoose.base.item.op.Operation.Status.FAIL_IO;
import static com.emc.mongoose.base.item.op.Operation.Status.FAIL_UNKNOWN;
import static com.emc.mongoose.base.item.op.Operation.Status.SUCC;
import static com.emc.mongoose.storage.driver.pulsar.Constants.MAX_MSG_SIZE;
import static java.lang.System.currentTimeMillis;
import static java.lang.System.nanoTime;
import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static org.apache.pulsar.client.api.SubscriptionInitialPosition.Earliest;
import static org.apache.pulsar.client.api.SubscriptionInitialPosition.Latest;

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
import org.apache.pulsar.client.api.SubscriptionInitialPosition;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class PulsarStorageDriver<I extends DataItem, O extends DataOperation<I>>
extends CoopStorageDriverBase<I, O> {

	protected final int storageNodePort;
	protected final String[] storageNodeAddrs;
	protected final CompressionType producerCompressionType;
	protected final SubscriptionInitialPosition initPos;
	protected final int batchSize;
	protected final boolean producerBatchingFlag;
	protected final long producerBatchingDelayMicros;
	protected final boolean producerRecordTimeFlag;

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
		this.batchSize = batchSize;
		val driverConfig = storageConfig.configVal("driver");
		// create config
		val createConfig = driverConfig.configVal("create");
		val batchConfig = createConfig.configVal("batch");
		this.producerBatchingFlag = batchConfig.boolVal("enabled");
		this.producerBatchingDelayMicros = batchConfig.longVal("delayMicros");
		val compressionRaw = (String) createConfig.val("compression");
		try {
			this.producerCompressionType = CompressionType.valueOf(compressionRaw.toUpperCase());
		} catch(final Exception e) {
			val compressionTypes = Arrays
				.stream(CompressionType.values())
				.map(Enum::toString)
				.map(String::toLowerCase)
				.collect(Collectors.joining(", "));
			throw new IllegalConfigurationException(
				"Invalid compression type value: \"" + compressionRaw + "\", valid values are: " + compressionTypes
			);
		}
		this.producerRecordTimeFlag = createConfig.boolVal("timestamp");
		// read config
		val readConfig = driverConfig.configVal("read");
		val tailReadFlag = readConfig.boolVal("tail");
		// misc config
		initPos = tailReadFlag ? Latest : Earliest;
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
		this.requestAuthTokenFunc = null;
		this.requestNewPathFunc = null;
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
					.enableBatching(producerBatchingFlag)
					.batchingMaxMessages(batchSize)
					.batchingMaxPublishDelay(producerBatchingDelayMicros, MICROSECONDS)
					.compressionType(producerCompressionType)
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
					.subscriptionInitialPosition(initPos)
					.subscriptionName(Long.toString(nanoTime()))
					.subscribe();
			} catch(final Exception e) {
				LogUtil.exception(
					Level.ERROR, e, "Failed to create a consumer for the node \"{}\" and topic \"{}\"",
					nodeAddr, topicName
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
				return submitCreate(op);
			case READ:
				return submitRead(op);
			default:
				throw new AssertionError("Unexpected operation type: " + opType);
		}
	}

	@SuppressWarnings("StatementWithEmptyBody")
	@Override
	protected final int submit(final List<O> ops, final int from, final int to)
	throws IllegalStateException {
		val anyOp = ops.get(from);
		val opType = anyOp.type();
		switch(opType) {
			case NOOP:
				for(var i = from; i < to && noop(ops.get(i)); i ++);
				return to - from;
			case CREATE:
				for(var i = from; i < to && submitCreate(ops.get(i)); i ++);
				return to - from;
			case READ:
				for(var i = from; i < to && submitRead(ops.get(i)); i++);
				return to - from;
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

	protected boolean submitCreate(final O op) {
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
					val msgBuilder = producer.newMessage();
					if(msgSize > 0) {
						val msgData = ByteBuffer.allocate((int) msgSize);
						for(var n = 0L; n < msgSize; n += item.read(msgData)); // copy the data to the buffer
						msgBuilder.value(msgData.array());
					}
					if(producerRecordTimeFlag) {
						msgBuilder.eventTime(currentTimeMillis());
					}
					op.startRequest();
					val f = msgBuilder.sendAsync();
					try {
						op.finishRequest();
					} catch(final IllegalStateException ignored) {
					}
					f.handle((msgId, thrown) -> handleMessageTransferred(op, thrown, msgSize));
				} else {
					return false;
				}
			}
		} catch(final IOException e) {
			throw new AssertionError(e);
		}
		return true;
	}

	protected final void failOperation(final O op, final Status status) {
		op.status(status);
		handleCompleted(op);
	}

	protected Object handleMessageTransferred(final O op, final Throwable thrown, final long transferSize) {
		try {
			if(null == thrown) {
				op.startResponse();
				op.finishResponse();
				op.countBytesDone(transferSize);
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

	protected boolean submitRead(final O op) {
		val nodeAddr = op.nodeAddr();
		val consumerFunc = consumerFuncCache.computeIfAbsent(nodeAddr, ConsumerFunctionImpl::new);
		val topicName = op.dstPath();
		val consumer = consumerCache.computeIfAbsent(topicName, consumerFunc);
		if(concurrencyThrottle.tryAcquire()) {
			op.startRequest();
			val f = consumer.receiveAsync();
			try {
				op.finishRequest();
			} catch(final IllegalStateException ignored) {
			}
			f.handle((msg, thrown) -> handleMessageRead(op, thrown, msg));
			return true;
		}
		return false;
	}

	protected Object handleMessageRead(final O op, final Throwable thrown, final Message<byte[]> msg) {
		val msgSize = msg.getData().length;
		if(Latest.equals(initPos)) { // tail read, try to determine the end-to-end time
			val e2eTimeMillis = currentTimeMillis() - msg.getEventTime();
			if(e2eTimeMillis > 0) {
				Loggers.OP_TRACES.info(new EndToEndLogMessage(msg.getMessageId(), msgSize, e2eTimeMillis));
			} else {
				Loggers.ERR.warn(
					"{}: publish time is in the future for the message \"{}\"", stepId, msg.getMessageId()
				);
			}
		}
		return handleMessageTransferred(op, thrown, msgSize);
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
		return Util.makeNewItems(itemFactory, path, prefix, count);
	}

	@Override
	public void adjustIoBuffers(final long avgTransferSize, final OpType opType) {
	}

	@Override
	protected void doClose()
	throws IOException {
		producerFuncCache.clear();
		Util.closeAll(producerCache.values());
		consumerFuncCache.clear();
		Util.closeAll(consumerCache.values());
		Util.closeAll(clients.values());
		clients.clear();
		super.doClose();
	}
}

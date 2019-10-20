package com.emc.mongoose.storage.driver.pulsar.io.create;

import com.emc.mongoose.base.config.IllegalConfigurationException;
import com.emc.mongoose.base.item.DataItem;
import com.emc.mongoose.base.item.op.data.DataOperation;
import com.emc.mongoose.base.logging.LogUtil;
import com.emc.mongoose.storage.driver.pulsar.PulsarStorageDriver;
import com.emc.mongoose.storage.driver.pulsar.io.SpecificOperationDriver;
import com.emc.mongoose.storage.driver.pulsar.io.SpecificOperationDriverBase;
import com.github.akurilov.confuse.Config;
import lombok.Value;
import lombok.val;
import org.apache.logging.log4j.Level;
import org.apache.pulsar.client.api.CompressionType;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

import static com.emc.mongoose.base.item.op.Operation.Status.FAIL_IO;
import static com.emc.mongoose.storage.driver.pulsar.Constants.MAX_MSG_SIZE;

public class CreateDriver<I extends DataItem, O extends DataOperation<I>>
extends SpecificOperationDriverBase<I, O>
implements SpecificOperationDriver<I, O> {

	protected final CompressionType compressionType;

	public CreateDriver(
		final PulsarStorageDriver<I, O> driver,
		final Map<String, PulsarClient> clients,
		final Semaphore concurrencyThrottle,
		final Config config
	) throws IllegalConfigurationException {
		super(driver, clients, concurrencyThrottle);
		val compressionRaw = (String) config.val("compression");
		try {
			compressionType = CompressionType.valueOf(compressionRaw.toUpperCase());
		} catch(final Exception e) {
			throw new IllegalConfigurationException(
				"Invalid compression type value: \"" + compressionRaw + "\", valid values are: " +
					Arrays
						.stream(CompressionType.values())
						.map(Enum::toString)
						.map(String::toLowerCase)
						.collect(Collectors.joining(", "))
			);
		}
	}

	private final Map<String, ProducerFunction> producerFuncCache = new ConcurrentHashMap<>();
	private final Map<String, Producer<byte[]>> producerCache = new ConcurrentHashMap<>();

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
					.compressionType(compressionType)
					.create();
			} catch(final Exception e) {
				LogUtil.exception(
					Level.ERROR, e, "Failed to create a producer for the node \"{}\" and topic \"{}\"",
					nodeAddr, topicName
				);
			}
			return producer;
		}
	}

	@Override
	public boolean submit(final O op) {
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
					f.handle((msgId, thrown) -> handleCompleted(op, thrown, msgSize));
				} else {
					return false;
				}
			}
		} catch(final IOException e) {
			throw new AssertionError(e);
		}
		return true;
	}

	@Override
	public int submit(final List<O> ops, final int from, final int to) {
		for(var i = from; i < to; i ++) {
			submit(ops.get(i));
		}
		return to - from;
	}

	@Override
	public void close() {
		producerFuncCache.clear();
		closeAll(producerCache.values());
	}
}

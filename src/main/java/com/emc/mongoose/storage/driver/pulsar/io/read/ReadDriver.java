package com.emc.mongoose.storage.driver.pulsar.io.read;

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
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.SubscriptionInitialPosition;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

import static org.apache.pulsar.client.api.SubscriptionInitialPosition.Earliest;
import static org.apache.pulsar.client.api.SubscriptionInitialPosition.Latest;

public class ReadDriver<I extends DataItem, O extends DataOperation<I>>
extends SpecificOperationDriverBase<I, O>
implements SpecificOperationDriver<I, O> {

	protected final SubscriptionInitialPosition initPos;

	public ReadDriver(
		final PulsarStorageDriver<I, O> driver,
		final Map<String, PulsarClient> clients,
		final Semaphore concurrencyThrottle,
		final Config config
	) {
		super(driver, clients, concurrencyThrottle);
		val tailReadFlag = config.boolVal("tail");
		initPos = tailReadFlag ? Latest : Earliest;
	}

	private final Map<String, ConsumerFunction> consumerFuncCache = new ConcurrentHashMap<>();
	private final Map<String, Consumer<byte[]>> consumerCache = new ConcurrentHashMap<>();

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
					.subscriptionName(Long.toString(System.nanoTime()))
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
	public boolean submit(final O op) {
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
			f.handle((msg, thrown) -> handleCompleted(op, thrown, msg.getData().length));
			return true;
		}
		return false;
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
		consumerFuncCache.clear();
		closeAll(consumerCache.values());
	}
}

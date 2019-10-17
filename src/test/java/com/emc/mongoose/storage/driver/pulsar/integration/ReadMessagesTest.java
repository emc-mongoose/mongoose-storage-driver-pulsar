package com.emc.mongoose.storage.driver.pulsar.integration;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.SubscriptionInitialPosition;
import org.junit.Test;

import java.util.HashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import static com.emc.mongoose.base.Exceptions.throwUncheckedIfInterrupted;
import static com.github.akurilov.commons.lang.Exceptions.throwUnchecked;
import static java.lang.System.nanoTime;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ReadMessagesTest {

	@Test
	public final void testReadFromTheBeginningSingleMessage()
	throws Exception {
		final var topicName = Long.toString(nanoTime());
		try(
			final var client = PulsarClient
				.builder()
				.serviceUrl("pulsar://localhost:6650")
				.build()
		) {
			try(
				final var producer = client
					.newProducer()
					.topic(topicName)
					.create()
			) {
				final var result = producer
					.newMessage()
					.value(new byte[] {1})
					.sendAsync()
					.handle((msgId, thrown) -> thrown == null ? msgId : thrown)
					.get(1, TimeUnit.SECONDS);
				assertFalse(result instanceof Throwable);
				assertTrue(result instanceof MessageId);
				final var msgId = (MessageId) result;
				System.out.println(msgId);
			}
			try(
				final var consumer = client
					.newConsumer()
					.subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
					.topic(topicName)
					.subscriptionName("subscription1")
					.subscribe()
			) {
				final var msg = consumer.receive(1, TimeUnit.SECONDS);
				final var payload = msg.getData();
				assertArrayEquals(new byte[] {1}, payload);
			}
		}
	}

	@Test
	public void testReadMultipleMessages()
	throws Exception {
		final var topicName = Long.toString(nanoTime());
		try(
			final var client = PulsarClient
				.builder()
				.serviceUrl("pulsar://localhost:6650")
				.build()
		) {
			final var payload = new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
			try(
				final var producer = client
					.newProducer()
					.batchingMaxMessages(100)
					.topic(topicName)
					.create()
			) {
				for(var i = 0; i < 100; i ++) {
					producer
						.newMessage()
						.value(payload)
						.sendAsync();
					System.out.println("Message #" + i + " has been sent");
				}
				producer
					.flushAsync()
					.handle(
						(v, thrown) -> {
							throwUncheckedIfInterrupted(thrown);
							if(null != thrown) {
								fail(thrown.getMessage());
							}
							return v;
						}
					)
					.get(100, TimeUnit.SECONDS);
			}
			try(
				final var consumer = client
					.newConsumer()
					.subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
					.topic(topicName)
					.subscriptionName("subscription1")
					.subscribe()
			) {
				var readCount = 0;
				while(readCount < 100) {
					final var msg = consumer.receive(100, TimeUnit.SECONDS);
					final var actualPayload = msg.getData();
					assertArrayEquals(payload, actualPayload);
					System.out.println("Message #" + readCount + " has been read");
					readCount ++;
				}
			}
		}
	}

	@Test
	public final void testMultiTopicTailRead()
	throws Exception {
		final var topicNamePrefix = Long.toString(nanoTime());
		final var topicNames = new String[] {
			topicNamePrefix + "0",
			topicNamePrefix + "1",
			topicNamePrefix + "2",
			topicNamePrefix + "3"
		};
		final var producerCount = topicNames.length;
		final var consumerCount = 1;
		final var payload = new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
		final var producedCount = new LongAdder();
		final var consumedCount = new LongAdder();
		final var executor = Executors.newFixedThreadPool(producerCount + consumerCount);
		try(
			final var client = PulsarClient
				.builder()
				.serviceUrl("pulsar://localhost:6650")
				.build()
		) {
			for(var i = 0; i < producerCount; i ++) {
				final var topicName = topicNames[i];
				executor.submit(
					() -> {
						try(
							final var producer = client
								.newProducer()
								.topic(topicName)
								.create()
						) {
							while(true) {
								producer
									.newMessage()
									.value(payload)
									.sendAsync()
									.get();
								producedCount.increment();
							}
						} catch(final InterruptedException e) {
							throwUnchecked(e);
						} catch(final Exception e) {
							fail(e.getMessage());
						}
					}
				);
			}
			for(var i = 0; i < consumerCount; i ++) {
				executor.submit(
					() -> {
						try(
							final var consumer = client
								.newConsumer()
								.subscriptionInitialPosition(SubscriptionInitialPosition.Latest)
								.topic(topicNames)
								.subscriptionName("subscription1")
								.subscribe()
						) {
							while(true) {
								final var msg = consumer.receive(1, TimeUnit.DAYS);
								final var actualPayload = msg.getData();
								assertArrayEquals(payload, actualPayload);
								consumedCount.increment();
							}
						} catch(final PulsarClientException e) {
							fail(e.getMessage());
						}
					}
				);
			}
			TimeUnit.SECONDS.sleep(10);
			executor.shutdownNow();
			final var producedCount_ = producedCount.sum();
			assertTrue(producedCount_ > 0);
			//noinspection IntegerDivisionInFloatingPointContext
			assertEquals(producedCount_, consumedCount.sum(), producedCount_ / 1000);
		}
	}
}

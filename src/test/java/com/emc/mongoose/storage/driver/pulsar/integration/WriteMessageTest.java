package com.emc.mongoose.storage.driver.pulsar.integration;

import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.PulsarClient;
import org.junit.Test;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.emc.mongoose.base.Constants.MIB;
import static com.emc.mongoose.base.Exceptions.throwUncheckedIfInterrupted;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class WriteMessageTest {

	@Test
	public void testWriteMessage1B()
	throws Exception {
		try(
			final var client = PulsarClient
				.builder()
				//.allowTlsInsecureConnection(true)
				//.authentication(new AuthenticationDisabled())
				//.connectionsPerBroker(10)
				//.connectionTimeout(1, TimeUnit.DAYS)
				//.enableTcpNoDelay(true)
				//.enableTlsHostnameVerification(false)
				//.ioThreads(Runtime.getRuntime().availableProcessors())
				//.listenerThreads(1)
				//.keepAliveInterval(1, TimeUnit.DAYS)
				//.maxConcurrentLookupRequests(1)
				.serviceUrl("pulsar://localhost:6650")
				//.statsInterval(1, TimeUnit.DAYS)
				.build()
		) {
			try(
				final var producer = client
					.newProducer()
					//.batchingMaxMessages(1)
					.topic("test")
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
		}
	}

	@Test
	public void testWriteMessage1MB()
	throws Exception {
		try(
			final var client = PulsarClient
				.builder()
				.serviceUrl("pulsar://localhost:6650")
				.build()
		) {
			try(
				final var producer = client
					.newProducer()
					.topic("test")
					.create()
			) {
				final var payload = new byte[MIB];
				for(var i = 0; i < MIB; i ++) {
					payload[i] = (byte) i;
				}
				final var result = producer
					.newMessage()
					.value(payload)
					.sendAsync()
					.handle((msgId, thrown) -> thrown == null ? msgId : thrown)
					.get(1, TimeUnit.SECONDS);
				assertFalse(result instanceof Throwable);
				assertTrue(result instanceof MessageId);
				final var msgId = (MessageId) result;
				System.out.println(msgId);
			}
		}
	}

	@Test
	public void testWriteMessageWithKey()
	throws Exception {
		try(
			final var client = PulsarClient
				.builder()
				.serviceUrl("pulsar://localhost:6650")
				.build()
		) {
			try(
				final var producer = client
					.newProducer()
					.topic("test")
					.create()
			) {
				final var result = producer
					.newMessage()
					.key("foo")
					.value(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
					.sendAsync()
					.handle((msgId, thrown) -> thrown == null ? msgId : thrown)
					.get(10, TimeUnit.SECONDS);
				assertFalse(result instanceof Throwable);
				assertTrue(result instanceof MessageId);
				final var msgId = (MessageId) result;
				System.out.println(msgId);
			}
		}
	}

	@Test
	public void testWriteMessagesBatch()
	throws Exception {
		try(
			final var client = PulsarClient
				.builder()
				.serviceUrl("pulsar://localhost:6650")
				.build()
		) {
			try(
				final var producer = client
					.newProducer()
					.batchingMaxMessages(100)
					.topic("test")
					.create()
			) {
				final var payload = new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
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
		}
	}
}

package com.emc.mongoose.storage.driver.pulsar.cache;

import org.apache.pulsar.client.api.Producer;

import java.util.function.Function;

public interface ProducerFunction
extends Function<String, Producer<byte[]>> {
}

package com.emc.mongoose.storage.driver.pulsar.io.create;

import org.apache.pulsar.client.api.Producer;

import java.util.function.Function;

public interface ProducerFunction
extends Function<String, Producer<byte[]>> {
}

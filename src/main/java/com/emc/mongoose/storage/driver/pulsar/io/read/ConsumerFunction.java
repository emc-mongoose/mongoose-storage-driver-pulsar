package com.emc.mongoose.storage.driver.pulsar.io.read;

import org.apache.pulsar.client.api.Consumer;

import java.util.function.Function;

public interface ConsumerFunction
extends Function<String, Consumer<byte[]>> {
}

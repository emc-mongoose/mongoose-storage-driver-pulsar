package com.emc.mongoose.storage.driver.pulsar.io;

import com.emc.mongoose.base.item.DataItem;
import com.emc.mongoose.base.item.op.data.DataOperation;

import java.io.Closeable;
import java.util.List;

public interface SpecificOperationDriver<I extends DataItem, O extends DataOperation<I>>
extends Closeable {

	boolean submit(final O op);

	int submit(final List<O> ops, final int from, final int to);
}

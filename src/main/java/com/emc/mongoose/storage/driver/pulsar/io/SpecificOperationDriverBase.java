package com.emc.mongoose.storage.driver.pulsar.io;

import com.emc.mongoose.base.item.DataItem;
import static com.emc.mongoose.base.item.op.Operation.Status;
import static com.emc.mongoose.base.item.op.Operation.Status.FAIL_UNKNOWN;
import static com.emc.mongoose.base.item.op.Operation.Status.SUCC;

import com.emc.mongoose.base.item.op.data.DataOperation;
import com.emc.mongoose.base.logging.LogUtil;
import com.emc.mongoose.storage.driver.pulsar.PulsarStorageDriver;
import org.apache.logging.log4j.Level;
import org.apache.pulsar.client.api.PulsarClient;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.Semaphore;

public abstract class SpecificOperationDriverBase<I extends DataItem, O extends DataOperation<I>>
implements SpecificOperationDriver<I, O> {

	protected final PulsarStorageDriver<I, O> driver;
	protected final Map<String, PulsarClient> clients;
	protected final Semaphore concurrencyThrottle;

	protected SpecificOperationDriverBase(
		final PulsarStorageDriver<I, O> driver,
		final Map<String, PulsarClient> clients,
		final Semaphore concurrencyThrottle
	) {
		this.driver = driver;
		this.clients = clients;
		this.concurrencyThrottle = concurrencyThrottle;
	}

	protected Object handleCompleted(final O op, final Throwable thrown, final long transferredByteCount) {
		try {
			if(null == thrown) {
				op.startResponse();
				op.finishResponse();
				op.countBytesDone(transferredByteCount);
				op.status(SUCC);
				driver.handleCompletedOperation(op);
			} else {
				failOperation(op, FAIL_UNKNOWN);
			}
		} finally {
			concurrencyThrottle.release();
		}
		return null;
	}

	protected final void failOperation(final O op, final Status status) {
		op.status(status);
		driver.handleCompletedOperation(op);
	}

	protected static void closeAll(final Collection<? extends AutoCloseable> closeables) {
		closeables
			.parallelStream()
			.forEach(
				entry -> {
					try {
						entry.close();
					} catch(final Exception e) {
						LogUtil.exception(Level.WARN, e, "Failed to close: {}", entry);
					}
				}
			);
		closeables.clear();
	}
}

package com.emc.mongoose.storage.driver.pulsar;

import com.emc.mongoose.base.config.IllegalConfigurationException;
import com.emc.mongoose.base.data.DataInput;
import com.emc.mongoose.base.item.DataItem;
import com.emc.mongoose.base.item.ItemFactory;
import com.emc.mongoose.base.item.op.OpType;
import com.emc.mongoose.base.item.op.data.DataOperation;
import com.emc.mongoose.base.storage.Credential;
import com.emc.mongoose.storage.driver.coop.CoopStorageDriverBase;
import com.emc.mongoose.storage.driver.pulsar.io.SpecificOperationDriver;
import com.emc.mongoose.storage.driver.pulsar.io.create.CreateDriver;
import com.emc.mongoose.storage.driver.pulsar.io.read.ReadDriver;
import static com.emc.mongoose.base.item.op.Operation.Status.SUCC;
import com.github.akurilov.confuse.Config;
import lombok.val;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;

import java.io.EOFException;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public class PulsarStorageDriver<I extends DataItem, O extends DataOperation<I>>
extends CoopStorageDriverBase<I, O> {

	protected final int storageNodePort;
	protected final String[] storageNodeAddrs;
	private final Map<String, PulsarClient> clients = new ConcurrentHashMap<>();
	private final SpecificOperationDriver<I, O> createDriver;
	private final SpecificOperationDriver<I, O> readDriver;

	public PulsarStorageDriver(
		final String stepId, final DataInput dataInput, final Config storageConfig, final boolean verifyFlag,
		final int batchSize
	) throws IllegalConfigurationException {
		super(stepId, dataInput, storageConfig, verifyFlag, batchSize);
		val driverConfig = storageConfig.configVal("driver");
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
		val createConfig = driverConfig.configVal("create");
		this.createDriver = new CreateDriver<>(this, clients, concurrencyThrottle, createConfig);
		val readConfig = driverConfig.configVal("read");
		this.readDriver = new ReadDriver<>(this, clients, concurrencyThrottle, readConfig);
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
				return createDriver.submit(op);
			case READ:
				return readDriver.submit(op);
			default:
				throw new AssertionError("Unexpected operation type: " + opType);
		}
	}

	@Override
	protected final int submit(final List<O> ops, final int from, final int to)
	throws IllegalStateException {
		val anyOp = ops.get(from);
		val opType = anyOp.type();
		switch(opType) {
			case NOOP:
				return noop(ops, from, to);
			case CREATE:
				return createDriver.submit(ops, from, to);
			case READ:
				return readDriver.submit(ops, from, to);
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

	final int noop(final List<O> ops, final int from, final int to) {
		for(var i = from; i < to; i ++) {
			noop(ops.get(i));
		}
		return to - from;
	}

	public final boolean handleCompletedOperation(final O op) {
		return super.handleCompleted(op);
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
	protected final void doClose()
	throws IOException {
		createDriver.close();
		readDriver.close();
		Util.closeAll(clients.values());
		clients.clear();
	}
}

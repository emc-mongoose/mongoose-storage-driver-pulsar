package com.emc.mongoose.storage.driver.pulsar;

import com.emc.mongoose.base.data.DataInput;
import com.emc.mongoose.base.item.DataItem;
import com.emc.mongoose.base.item.ItemFactory;
import com.emc.mongoose.base.item.op.OpType;
import com.emc.mongoose.base.item.op.data.DataOperation;
import com.emc.mongoose.base.storage.Credential;
import com.emc.mongoose.storage.driver.coop.CoopStorageDriverBase;
import com.github.akurilov.confuse.Config;

import java.io.IOException;
import java.util.List;

public class PulsarStorageDriver<I extends DataItem, O extends DataOperation<I>>
extends CoopStorageDriverBase<I, O> {

    public PulsarStorageDriver(
            final String stepId,
            final DataInput dataInput,
            final Config storageConfig,
            final boolean verifyFlag,
            final int batchSize
    ) {
        super(stepId, dataInput, storageConfig, verifyFlag, batchSize);
    }

    @Override
    protected boolean submit(O op)
    throws IllegalStateException {

    }

    @Override
    protected int submit(List<O> ops, int from, int to) throws IllegalStateException {
        return 0;
    }

    @Override
    protected int submit(List<O> ops) throws IllegalStateException {
        return 0;
    }

    @Override
    protected String requestNewPath(String path) {
        return null;
    }

    @Override
    protected String requestNewAuthToken(Credential credential) {
        return null;
    }

    @Override
    public List<I> list(ItemFactory<I> itemFactory, String path, String prefix, int idRadix, I lastPrevItem, int count)
    throws IOException {
        return null;
    }

    @Override
    public void adjustIoBuffers(long avgTransferSize, OpType opType) {

    }
}

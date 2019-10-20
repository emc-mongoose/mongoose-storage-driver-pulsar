package com.emc.mongoose.storage.driver.pulsar;

import com.emc.mongoose.base.config.IllegalConfigurationException;
import com.emc.mongoose.base.data.DataInput;
import com.emc.mongoose.base.env.ExtensionBase;
import com.emc.mongoose.base.item.DataItem;
import com.emc.mongoose.base.item.op.data.DataOperation;
import com.emc.mongoose.base.storage.driver.StorageDriverFactory;
import com.github.akurilov.confuse.Config;
import com.github.akurilov.confuse.SchemaProvider;
import com.github.akurilov.confuse.io.yaml.YamlSchemaProviderBase;

import java.io.InputStream;
import java.util.List;

import static com.emc.mongoose.base.Constants.APP_NAME;
import static com.emc.mongoose.storage.driver.pulsar.Constants.DRIVER_NAME;

public class PulsarStorageDriverExtension<
	I extends DataItem, O extends DataOperation<I>, T extends PulsarStorageDriver<I, O>
>
extends ExtensionBase
implements StorageDriverFactory<I, O, T> {

	private static final SchemaProvider SCHEMA_PROVIDER = new YamlSchemaProviderBase() {
		@Override
		protected final InputStream schemaInputStream() {
			return getClass().getResourceAsStream("/config-schema-storage-driver-" + DRIVER_NAME + ".yaml");
		}

		@Override
		public final String id() {
			return APP_NAME;
		}
	};
	private static final String DEFAULTS_FILE_NAME = "defaults-storage-driver-" + DRIVER_NAME + ".yaml";
	private static final List<String> RES_INSTALL_FILES = List.of("config/" + DEFAULTS_FILE_NAME);

	@Override
	public final String id() {
		return DRIVER_NAME;
	}

	@Override
	protected final String defaultsFileName() {
		return DEFAULTS_FILE_NAME;
	}

	@Override
	public final SchemaProvider schemaProvider() {
		return SCHEMA_PROVIDER;
	}

	@Override
	protected final List<String> resourceFilesToInstall() {
		return RES_INSTALL_FILES;
	}

	@Override
	public T create(
		final String stepId, final DataInput dataInput, final Config storageConfig, final boolean verifyFlag,
		final int batchSize
	) throws IllegalConfigurationException, InterruptedException {
		return (T) new PulsarStorageDriver<I, O>(stepId, dataInput, storageConfig, verifyFlag, batchSize);
	}
}

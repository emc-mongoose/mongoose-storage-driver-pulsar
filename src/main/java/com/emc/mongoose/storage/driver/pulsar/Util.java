package com.emc.mongoose.storage.driver.pulsar;

import com.emc.mongoose.base.item.DataItem;
import com.emc.mongoose.base.item.ItemFactory;
import com.emc.mongoose.base.logging.LogUtil;
import lombok.val;
import org.apache.logging.log4j.Level;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static com.emc.mongoose.base.item.op.Operation.SLASH;

public interface Util {

	static void closeAll(final Collection<? extends AutoCloseable> closeables) {
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

	static <I extends DataItem> List<I> makeNewItems(
		final ItemFactory<I> itemFactory,
		final String path,
		final String prefix,
		final int count
	) {
		val items = new ArrayList<I>();
		for (var i = 0; i < count; i++) {
			items.add(itemFactory.getItem(path + SLASH + (prefix == null ? i : prefix + i), 0, 0));
		}
		return items;
	}
}

package com.emc.mongoose.storage.driver.pulsar;

import com.emc.mongoose.base.logging.LogMessageBase;
import org.apache.pulsar.client.api.MessageId;

import static java.lang.System.lineSeparator;

public class EndToEndLogMessage
extends LogMessageBase {

	private static final String LINE_SEP = lineSeparator();

	private final MessageId msgId;
	private final long size;
	private final long timeMillis;

	public EndToEndLogMessage(final MessageId msgId, final long size, final long timeMillis) {
		this.msgId = msgId;
		this.size = size;
		this.timeMillis = timeMillis;
	}

	@Override
	public void formatTo(final StringBuilder buff) {
		buff
			.append(msgId).append(',')
			.append(size).append(',')
			.append(timeMillis).append(LINE_SEP);
	}
}

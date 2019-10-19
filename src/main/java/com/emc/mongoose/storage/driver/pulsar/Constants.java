package com.emc.mongoose.storage.driver.pulsar;

import static com.emc.mongoose.base.Constants.MIB;

public interface Constants {

    String DRIVER_NAME = "pulsar";
    int MAX_MSG_SIZE = 5 * MIB;
}

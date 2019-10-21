*** Settings ***

Library  OperatingSystem
Library  CSVLibrary
Library  OpTrace
Test Setup  Start Containers
Test Teardown  Stop Containers

*** Variables ***

${DATA_DIR} =  src/test/robot/api/storage/data
${LOG_DIR} =  build/log
${MONGOOSE_IMAGE_NAME} =  emcmongoose/mongoose-storage-driver-pulsar
${MONGOOSE_CONTAINER_DATA_DIR} =  /data
${MONGOOSE_CONTAINER_NAME} =  mongoose-storage-driver-pulsar

*** Test Cases ***

Create Messages Test
    [Timeout]  5 minutes
    [Tags]  create_messages
    ${node_addr} =  Get Environment Variable  SERVICE_HOST  127.0.0.1
    ${step_id} =  Set Variable  create_messages_test
    ${count_limit} =  Set Variable  1000
    Remove Directory  ${LOG_DIR}/${step_id}  recursive=True
    ${args} =  Catenate  SEPARATOR= \\\n\t
    ...  --load-batch-size=1000
    ...  --load-op-limit-count=${count_limit}
    ...  --load-step-id=${step_id}
    ...  --storage-driver-limit-concurrency=1000
    ...  --storage-net-node-addrs=${node_addr}
    &{env_params} =  Create Dictionary
    ${std_out} =  Execute Mongoose Scenario  ${DATA_DIR}  ${env_params}  ${args}
    Log  ${std_out}
    Validate Metrics Total Log File  ${step_id}  CREATE  ${count_limit}  0  1048576000

End To End Time Measurement Test
    [Timeout]  5 minutes
    [Tags]  e2e_time_measurement
    ${node_addr} =  Get Environment Variable  SERVICE_HOST  127.0.0.1
    ${step_id} =  Set Variable  e2e_time_measurement_test
    Remove Directory  ${LOG_DIR}/${step_id}  recursive=True
    ${args} =  Catenate  SEPARATOR= \\\n\t
    ...  --load-batch-size=1000
    ...  --read
    ...  --item-input-path=${step_id}
    ...  --storage-driver-read-tail
    ...  --load-op-recycle
    ...  --load-step-id=${step_id}
    &{env_params} =  Create Dictionary
    ${read_container_id} =  Start Mongoose Scenario  ${DATA_DIR}  ${env_params}  ${args}
    Log  ${read_container_id}
    ${count_limit} =  Set Variable  10000
    ${args} =  Catenate  SEPARATOR= \\\n\t
    ...  --item-data-size=10KB
    ...  --item-output-path=${step_id}
    ...  --load-batch-size=1000
    ...  --load-op-limit-count=${count_limit}
    ...  --load-step-id=${step_id}
    ...  --storage-net-node-addrs=${node_addr}
    &{env_params} =  Create Dictionary
    ${std_out} =  Execute Mongoose Scenario  ${DATA_DIR}  ${env_params}  ${args}
    Log  ${std_out}
    Sleep  10
    ${std_out} =  Run  docker logs ${read_container_id}
    Log  ${std_out}
    Run  docker kill ${read_container_id}
    Validate End To End Times Log File  file_name=${LOG_DIR}/${step_id}/op.trace.csv
    ...  count_limit=${count_limit}  msg_size=10240

*** Keyword ***

Start Mongoose Scenario
    [Arguments]   ${shared_data_dir}  ${env}  ${args}
    ${docker_env_vars} =  Evaluate  ' '.join(['-e %s=%s' % (key, value) for (key, value) in ${env}.items()])
    ${host_working_dir} =  Get Environment Variable  HOST_WORKING_DIR
    Log  Host working dir: ${host_working_dir}
    ${base_version} =  Get Environment Variable  BASE_VERSION
    ${image_version} =  Get Environment Variable  VERSION
    ${cmd} =  Catenate  SEPARATOR= \\\n\t
    ...  docker run
    ...  --detach
    ...  --network host
    ...  ${docker_env_vars}
    ...  --volume ${host_working_dir}/${shared_data_dir}:${MONGOOSE_CONTAINER_DATA_DIR}
    ...  --volume ${host_working_dir}/${LOG_DIR}:/root/.mongoose/${base_version}/log
    ...  ${MONGOOSE_IMAGE_NAME}:${image_version}
    ...  ${args}
    ${std_out} =  Run  ${cmd}
    [Return]  ${std_out}

Execute Mongoose Scenario
    [Timeout]  5 minutes
    [Arguments]   ${shared_data_dir}  ${env}  ${args}
    ${docker_env_vars} =  Evaluate  ' '.join(['-e %s=%s' % (key, value) for (key, value) in ${env}.items()])
    ${host_working_dir} =  Get Environment Variable  HOST_WORKING_DIR
    Log  Host working dir: ${host_working_dir}
    ${base_version} =  Get Environment Variable  BASE_VERSION
    ${image_version} =  Get Environment Variable  VERSION
    ${cmd} =  Catenate  SEPARATOR= \\\n\t
    ...  docker run
    ...  --name ${MONGOOSE_CONTAINER_NAME}
    ...  --network host
    ...  ${docker_env_vars}
    ...  --volume ${host_working_dir}/${shared_data_dir}:${MONGOOSE_CONTAINER_DATA_DIR}
    ...  --volume ${host_working_dir}/${LOG_DIR}:/root/.mongoose/${base_version}/log
    ...  ${MONGOOSE_IMAGE_NAME}:${image_version}
    ...  ${args}
    ${std_out} =  Run  ${cmd}
    [Return]  ${std_out}

Remove Mongoose Node
    ${std_out} =  Run  docker logs ${MONGOOSE_CONTAINER_NAME}
    Log  ${std_out}
    Run  docker stop ${MONGOOSE_CONTAINER_NAME}
    Run  docker rm ${MONGOOSE_CONTAINER_NAME}

Start Containers
    [Return]  0

Stop Containers
    Remove Mongoose Node

Validate Metrics Total Log File
    [Arguments]  ${step_id}  ${op_type}  ${count_succ}  ${count_fail}  ${transfer_size}
    @{metricsTotal} =  Read CSV File To Associative  ${LOG_DIR}/${step_id}/metrics.total.csv
    Should Be Equal As Strings  &{metricsTotal[0]}[OpType]  ${op_type}
    Should Be Equal As Strings  &{metricsTotal[0]}[CountSucc]  ${count_succ}
    Should Be Equal As Strings  &{metricsTotal[0]}[CountFail]  ${count_fail}
    Should Be Equal As Strings  &{metricsTotal[0]}[Size]  ${transfer_size}

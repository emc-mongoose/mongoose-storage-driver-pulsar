import csv
import sys
import traceback


class CreateTraceRecord:
	def __init__(self):
		pass
	time_start_micros = 0
	duration_micros = 0


def validate_end_to_end_times_log_file(file_name, count_limit, msg_size):
	count_limit = long(count_limit)
	count = 0
	with open(file_name, "rb") as op_trace_file:
		reader = csv.reader(op_trace_file)
		try:
			for row in reader:
				actual_msg_size = long(row[1])
				assert actual_msg_size == msg_size, \
					"Message payload size %d != expected %d" % (actual_msg_size, msg_size)
				e2e_time_millis = long(row[2])
				assert e2e_time_millis > 0, "Unexpected end-to-end time value: %d" % e2e_time_millis
				count += 1
			assert count == count_limit, \
				"Message count %d != count limit %d" % (count, count_limit)
		except TypeError as e:
			traceback.print_exc(file=sys.stdout)
			raise e

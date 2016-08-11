#
# Copyright 2015-2016 IBM Corporation
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# This script is useful for testing the action proxy (or its derivatives)
# in combination with delete-build-run.sh. Use it to initialize the action
# with "code" from a file e.g., python init.py mycode.[py, pl, sh].

import sys
import json
import requests
import codecs

DEST="http://localhost:8080/init"

with(codecs.open(sys.argv[1], "r", "utf-8")) as fp:
    contents = fp.read()

r = requests.post(DEST, json.dumps({ "value" : { "code" : contents } }))

print r.text

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

import sys
import subprocess
import codecs
sys.path.append('../actionProxy')
from actionproxy import ActionRunner, main, setRunner

SRC_EPILOGUE_FILE = "./epilogue.swift"
DEST_SCRIPT_FILE = "/swift3Action/spm-build/main.swift"
DEST_SCRIPT_DIR = "/swift3Action/spm-build"
DEST_BIN_FILE = "/swift3Action/spm-build/.build/debug/Action"
BUILD_PROCESS = [ "swift", "build", "-Xcc", "-fblocks"]

class Swift3Runner(ActionRunner):

    def __init__(self):
        ActionRunner.__init__(self, DEST_SCRIPT_FILE, DEST_BIN_FILE)

    def epilogue(self, fp):
        with codecs.open(SRC_EPILOGUE_FILE, "r", "utf-8") as ep:
            fp.write(ep.read())

    def build(self):
        p = subprocess.Popen(BUILD_PROCESS, cwd=DEST_SCRIPT_DIR)
        (o, e) = p.communicate()

        if o is not None:
            sys.stdout.write(o)

        if e is not None:
            sys.stderr.write(e)

if __name__ == "__main__":
    setRunner(Swift3Runner())
    main()

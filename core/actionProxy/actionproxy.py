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
import os
import stat
import json
import subprocess
import codecs
import flask
from gevent.wsgi import WSGIServer

class ActionRunner:

    LOG_SENTINEL = 'XXX_THE_END_OF_A_WHISK_ACTIVATION_XXX'

    def __init__(self, source = None, binary = None):
        defaultBinary = '/action/a.out'
        self.source = source if source else defaultBinary
        self.binary = binary if binary else defaultBinary

    def init(self, message):
        if 'code' in message:
            with codecs.open(self.source, 'w', 'utf-8') as fp:
                fp.write(str(message['code']))
                self.epilogue(fp)
            try:
                self.build()
            except Exception:
                None  # do nothing, verify will signal failure if binary not executable
        return self.verify()

    def epilogue(self, fp):
        return

    def build(self):
        perms = os.stat(self.binary)
        os.chmod(self.binary, st.st_mode | stat.S_IEXEC)

    def verify(self):
        return (os.path.isfile(self.binary) and os.access(self.binary, os.X_OK))

    def run(self, message):
        def error(msg):
            # fall through (exception and else case are handled the same way)
            sys.stdout.write('%s\n' % msg)
            return (502, { 'error': 'The action did not return a dictionary.'})

        # make sure to include all the env vars passed in by the invoker
        env = os.environ
        # as well as the input arguments (some runtimes may use this for input rather than the command line)
        if 'authKey' in message:
            env['AUTH_KEY'] = message['authKey']
        env['WHISK_INPUT'] = json.dumps(message)

        try:
            p = subprocess.Popen(
                [ self.binary, env['WHISK_INPUT'] ],
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                env=env)
        except Exception as e:
            return error(e)

        # run the process and wait until it completes.
        (o, e) = p.communicate()

        if o is not None:
            process_output_lines = o.strip().split('\n')
            last_line = process_output_lines[-1]
            for line in process_output_lines[:-1]:
                sys.stdout.write('%s\n' % line)
        else:
            last_line = '{}'

        if e is not None:
            sys.stderr.write(e)

        try:
            json_output = json.loads(last_line)
            if isinstance(json_output, dict):
                return (200, json_output)
            else:
                return error(last_line)
        except Exception:
            return error(last_line)

proxy = flask.Flask(__name__)
proxy.debug = False
runner = None

def setRunner(r):
    global runner
    runner = r

@proxy.route('/init', methods=['POST'])
def init():
    message = flask.request.get_json(force=True, silent=True)
    if message and not isinstance(message, dict):
        flask.abort(404)
    else:
        value = message.get('value', {}) if message else {}

    if not isinstance(value, dict):
        flask.abort(404)

    try:
        status = runner.init(value)
    except Exception as e:
        status = False

    if status is True:
        return ('OK', 200)
    else:
        response = flask.jsonify({'error': 'The action failed to generate or locate a binary. See logs for details.' })
        response.status_code = 502
        return response

@proxy.route('/run', methods=['POST'])
def run():
    def complete(response):
        # Add sentinel to stdout/stderr
        sys.stdout.write('%s\n' % ActionRunner.LOG_SENTINEL)
        sys.stderr.write('%s\n' % ActionRunner.LOG_SENTINEL)
        return response

    def error():
        response = flask.jsonify({'error': 'The action did not receive a dictionary as an argument.' })
        response.status_code = 404
        return complete(response)

    message = flask.request.get_json(force=True, silent=True)
    if message and not isinstance(message, dict):
        return error()
    else:
        value = message.get('value', {}) if message else {}
        if not isinstance(value, dict):
            return error()

    if runner.verify():
        (code, result) = runner.run(value)
        response = flask.jsonify(result)
        response.status_code = code
    else:
        response = flask.jsonify({'error': 'The action failed to locate a binary. See logs for details.' })
        response.status_code = 502
    return complete(response)

def main():
    port = int(os.getenv('FLASK_PROXY_PORT', 8080))
    server = WSGIServer(('', port), proxy, log=None)
    server.serve_forever()

if __name__ == '__main__':
    setRunner(ActionRunner())
    main()

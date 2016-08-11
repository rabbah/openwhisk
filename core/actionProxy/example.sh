#!/bin/bash
#
# This file is a stub action and should be replaced with 
# a user action (script or compatible binary).
#
echo 'Actions may log to stdout or stderr. By convention, the last line of output must'
echo 'be a stringified JSON object which represents the result of the action. The input'
echo 'to the action is received as an argument from the command line.'
echo

if [[ -z $1 || $1 == '{}' ]]; then
    echo '{ "msg": "Hello from bash script!" }'
else
    echo $1 # echo the arguments back as the result
fi

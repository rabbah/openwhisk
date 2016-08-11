# Dockerfile for python action
FROM buildpack-deps:trusty

ENV DEBIAN_FRONTEND noninteractive

# Upgrade and install basic Python dependencies
RUN apt-get -y purge && \
    apt-get -y update && \
    apt-get -y install --fix-missing python2.7 python-distribute python-pip

# Install Python proxy support
RUN apt-get -y install --fix-missing python2.7-dev python-gevent python-flask
RUN apt-get clean

ENV FLASK_PROXY_PORT 8080

RUN mkdir -p /actionProxy
ADD actionproxy.py /actionProxy/

RUN mkdir -p /action
ADD example.sh /action/a.out
RUN chmod +x /action/a.out

CMD ["/bin/bash", "-c", "cd actionProxy && python -u actionproxy.py"]

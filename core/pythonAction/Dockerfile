# Dockerfile for python action
FROM openwhisk/dockerskeleton

ENV DEBIAN_FRONTEND noninteractive
 
ENV FLASK_PROXY_PORT 8080

RUN mkdir -p /pythonAction
ADD pythonaction.py /pythonAction/

CMD ["/bin/bash", "-c", "cd pythonAction && python -u pythonaction.py"]

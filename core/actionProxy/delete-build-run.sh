#!/usr/bin/env bash

# Useful for local testing.
# USE WITH CAUTION !!

# Removes all previously built instances.
remove=$(docker ps -a -q)
if [[ !  -z  $remove  ]]; then
    docker rm $remove
fi

image=${1:-whisk/dockerskeleton}
docker build -t $image .

echo ""
echo "  ---- RUNNING ---- "
echo ""

docker run -i -t -p 8080:8080 $image

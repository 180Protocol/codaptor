FROM openjdk:8u242

ARG DIST_FILE

LABEL maintainer=devops@bond180.com

ENV CORDAPTOR_API_ENDPOINT_ADDRESS=localhost:8500

EXPOSE 8500

ADD build/distributions/$DIST_FILE /cordaptor

WORKDIR /cordaptor

CMD ["./bin/cordaptor.sh"]

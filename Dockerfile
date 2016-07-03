FROM java:8-jdk
RUN apt-get update && apt-get install -y maven
RUN update-java-alternatives -s java-1.8.0-openjdk-amd64
ADD . /opt/wordnet_as_a_service
RUN cd /opt/wordnet_as_a_service && mvn compile
#this is the best way I found to download the plugin dependencies at build time
RUN cd /opt/wordnet_as_a_service && mvn exec:help
WORKDIR /opt/wordnet_as_a_service
CMD ["mvn","exec:java"]

FROM clojure:lein-2.7.1
COPY . /usr/src/app
WORKDIR /usr/src/app
RUN lein deps
RUN lein uberjar
CMD ["/usr/bin/java", "-jar", "target/uberjar/standalone.jar"]

FROM clojure:lein-2.7.1
COPY . /usr/src/app
WORKDIR /usr/src/app
RUN lein do deps, cljsbuild once prod
ENTRYPOINT ["lein"]
CMD ["run"]

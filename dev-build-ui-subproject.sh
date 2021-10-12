rm -Rf zipkin-server/target/classes/zipkin-lens
# first build node manually and copy NPM build to zipkin-lens directory
(cd zipkin-lens && npm install &&  npm run build && cp -R ./build ../zipkin-server/target/classes/zipkin-lens  )
#(cd zipkin-lens && cp -R ./build ../zipkin-server/target/classes/zipkin-lens )
# NPM build output will end up in the jar under the 'classes/zipkin-lens' directory
(cd zipkin-lens && cd ../zipkin-server/target/ && jar -cvf zipkin-lens.jar  classes )

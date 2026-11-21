# Build and run on of2

`build-of2.sh` repeats the build that produced the Groovy server with `db.t` and `db.i`. Run it on of2 from a checkout of this repo at `/home/rzhao/workspace/deephaven-core-faithray`.

```bash
extensions/db/build-of2.sh test     # compile and test :extensions-db
extensions/db/build-of2.sh server   # build the Groovy server and serve port 10000
```

From a laptop, forward that port and open the IDE:

```bash
ssh -f -N -o ExitOnForwardFailure=yes -L 10000:127.0.0.1:10000 of2
```

[http://localhost:10000/?psk=deephaven](http://localhost:10000/?psk=deephaven)

The pre-shared key is `deephaven`. Table files go to `/home/rzhao/deephaven-db`. Override either with `PSK` and `DATABASE_ROOT`.

## JDK 21

Gradle runs on JDK 21, and the project toolchain compiles with JDK 21 (`compilerVersion` defaults to 21). The script uses `JAVA_HOME=/home/rzhao/jdks/openjdk-21` (Temurin 21.0.13).

Install that JDK into the home directory when it is missing. of2 has no sudo.

```bash
cd /home/rzhao/jdks
curl -fL -o temurin-21.tar.gz \
  "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse?project=jdk"
mkdir -p temurin-21-extract
tar -xzf temurin-21.tar.gz -C temurin-21-extract
ln -sfn /home/rzhao/jdks/temurin-21-extract/jdk-21* /home/rzhao/jdks/openjdk-21
/home/rzhao/jdks/openjdk-21/bin/java -version
```

On this host, Gradle could not download a JDK 21 toolchain by itself (`No defined toolchain download url for LINUX on x86_64`). Point `JAVA_HOME` at the JDK above before `./gradlew`.

## Docker

`server` builds two things inside Docker:

- The web IDE. `web/client-ui/Dockerfile` runs `npm pack` for the published `@deephaven/code-studio`, embed-grid, embed-chart, and embed-widget packages inside `deephaven/node`, then the server serves those static files.
- The gRPC stubs. `proto/proto-backplane-grpc/Dockerfile` runs `protoc` from `deephaven/protoc-base` (`linux/amd64`) and writes Java, JavaScript, Python, Go, C++, and proto docs.

`test` compiles and runs `:extensions-db:test`. The server task is what pulls the IDE package and the full proto image.

of2 resolves `registry.npmjs.org` on the host, and Docker's bridge DNS does not. `build-of2.sh` passes a Gradle init script that sets every `DockerBuildImage` task to `network = host`. The check uses `class.name.contains("DockerBuildImage")` because Gradle decorates the task class as `DockerBuildImage_Decorated`.

## What the server runs

`server` is:

```bash
export JAVA_HOME=/home/rzhao/jdks/openjdk-21
export START_OPTS="-Xmx4g -DDatabase.root=/home/rzhao/deephaven-db"
./gradlew server-jetty-app:run -Pgroovy -Ppsk=deephaven --console=plain
```

Set `APPLICATION_DIR` to run Groovy application scripts at startup. For the kpipe market-data writer that is `/home/rzhao/workspace/kpipe/dh/app.d`. The script appends `md.ticks` into `db.i("md", "ticks")`. The console query is `dh/analyze_md.groovy` in that repo.

plus the Docker host-network init script. Wait for `Server started on port 10000`. Stop that process with SIGINT.

`db` is bound in the Groovy console by `extensions/db`. `extensions/db/gradle.properties` sets `io.deephaven.project.ProjectType=JAVA_PUBLIC`, which the build requires for a new Gradle project.

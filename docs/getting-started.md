# Getting started with Cordaptor

Cordaptor is designed for flexibility and ability to fit into any reasonable Corda use case.
Hence, there are a number of ways to quickly add Cordaptor to your stack:

* [Ad hoc use of Cordaptor embedded CorDapp bundle](#ad-hoc-use-of-cordaptor-embedded-cordapp-bundle)
* [Adding Cordaptor embedded CorDapp bundle to deployNodes task](#adding-cordaptor-embedded-cordapp-bundle-to-deploynodes-task)
* [Running Cordaptor standalone](#running-cordaptor-standalone)
* [Running Cordaptor in Docker](#running-cordaptor-in-docker)

## System requirements

Cordaptor preview is compatible with Corda 4.5 or higher. We are intending to make it
compatible with earlier versions of Corda 4, as well as support Corda 5 from day one.

## Ad hoc use of Cordaptor embedded CorDapp bundle

One way to use Cordaptor is to deploy it alongside your CorDapp(s) into the Corda node.
This is by far the easiest way to get started, and it is well suited for development or integration testing.
In this case, Cordaptor will use Corda node mechanisms to dynamically discover what CorDapps are
running alongside it, and will create an API for them.

Steps to follow:
1. Download Cordaptor embedded CorDapp bundle JAR file from
[github releases](https://github.com/b180tech/cordaptor/releases).
2. Drop the bundle file into `cordapps` directory of your Corda node.
3. Restart Corda node if it's running.
4. Open http://127.0.0.1:8500/swagger in your browser

Note that if you are developing your CorDapp locally and use `deployNodes` Gradle task
from [cordapp-template-java](https://github.com/corda/cordapp-template-java)
or [cordapp-template-kotlin](https://github.com/corda/cordapp-template-kotlin)
repos, then `cordapps` directories will be created for each configured Corda node
at `<project home>/build/nodes/<node name>/cordapps`. However, you may find it easier to use
the approach from the next section for mode automation.

Adding Cordaptor embedded CorDapp bundle to a Corda node does not require changes to `nodeInfo` files,
because the bundle CorDapp does not provide any contract or state classes.

Note when running as embedded CorDapp inside a Corda node, Cordaptor is instantiated as a
[node service](https://docs.corda.net/docs/corda-os/4.6/node-services.html). It will use internal API
available to Corda services to initiate flows. Corda security model requires flow classes to be annotated
with `StartableByService` annotation for them to be available for initiation by a service.

## Adding Cordaptor embedded CorDapp bundle to deployNodes task

If you are developing your CorDapp locally and use `deployNodes` Gradle task
from [cordapp-template-java](https://github.com/corda/cordapp-template-java)
or [cordapp-template-kotlin](https://github.com/corda/cordapp-template-kotlin),
you may want to use this method. It is only slightly more involved than the other one,
but will make sure that Cordaptor is automatically added to Corda node every time you redeploy
the network with `deployNodes` task.

Steps to follow:
1. In your IDE or text editor of choice open `build.gradle` file in the project home directory.
2. Make following changes to relevant `node` section(s) under `deployNodes` task definition:  
    ```$gradle
    node {
        name "..."
        cordapp "tech.b180.cordaptor:cordaptor-bundle-rest-embedded:0.1.0" // <-- add this line
        p2pPort 10002
        rpcSettings {
            address("localhost:10003")
            adminAddress("localhost:10043")
        }
    }
    ```  
    If you add Cordaptor bundle to more than one node, make sure you assign different listen addresses.
    Otherwise, all Cordaptor services will attempt to bind to the same port, which will cause all but one
    failing to start. See [Configuration](./configuration.md) for details.
3. Add Cordaptor bundle to the dependencies section of your `build.gradle` file:
    ```gradle
    dependencies {
        cordapp "tech.b180.cordaptor:cordaptor-bundle-rest-embedded:0.1.0"
    }
    ```
4. Run `deployNode` Gradle task:  
   For Windows: `./gradlew.bat deployNodes`  
   For Linux/Mac: `./gradlew deployNodes`
5. Start nodes in the generated Corda network using `runnodes.bat` or `runnodes.sh`
in directory `<project home>/build/nodes`
6. Open http://127.0.0.1:8500/swagger in your browser

Note when running as embedded CorDapp inside a Corda node, Cordaptor is instantiated as a
[node service](https://docs.corda.net/docs/corda-os/4.6/node-services.html). It will use internal API
available to Corda services to initiate flows. Corda security model requires flow classes to be annotated
with `StartableByService` annotation for them to be available for initiation by a service.

## Running Cordaptor standalone

This is currently available on Linux/Mac only. You can run Cordaptor standalone on Windows using Docker, see
[next section](#running-cordaptor-in-docker) for details.

Unlike the embedded bundle, which is running inside a Corda node, standalone Cordaptor runs as a separate process
and establishes Corda RPC connection to the node. This requires Corda node to have RPC user accounts with
appropriate permissions.

Using Cordaptor standalone is recommended for deployments where many API users are likely, because Cordaptor can
take the load off the underlying Corda node and continue to be available during node restarts. Read more about
pros and cons of different deployment models for Cordaptor in the [Architecture](./architecture.md) guide.

Cordaptor standalone requires a number of environment variables to be set correctly:
- `CORDA_RPC_NODE_ADDRESS` - hostname:port for Corda RPC connection
- `CORDA_RPC_USERNAME` - username to use when establishing RPC connection to the node
- `CORDA_RPC_PASSWORD` - password to use when establishing RPC connection to the node

Steps to follow:
1. Configure RPC user for the Corda node.  
   Details would vary depending on how exactly your node is configured. However, if you are using templates
   from [cordapp-template-java](https://github.com/corda/cordapp-template-java)
   or [cordapp-template-kotlin](https://github.com/corda/cordapp-template-kotlin)
   repos, which rely on `deployNodes` Gradle task, you can add the following line to the relevant `node`
   configuration block in `build.gradle` file:  
   `rpcUsers = [[ user: "<username>", "password": "<password>", "permissions": ["ALL"]]]`  
   Note this will create a user with admin permissions on the node.
2. Download and unpack Cordaptor standalone distribution tar file from
[github releases](https://github.com/b180tech/cordaptor/releases).
3. Copy JAR files of CorDapps you want Cordaptor to generate OpenAPI for to `<cordaptor home>/cordapps` directory.  
   Note this is a requirement of Corda RPC as well.
4. Start a new terminal windows and set the above environment variables.
5. Run the following command in the terminal window:  
   `<cordaptor home>/bin/cordaptor.sh`
6. Open http://127.0.0.1:8500/swagger in your browser

## Running Cordaptor in Docker

You can run Cordaptor in Docker or Kubernetes using official Docker image `b180tech/cordaptor` hosted on Dockerhub.
This is highly flexible deployment model compatible with Docker for Windows, Kubernetes,
Amazon Elastic Container Service, and Amazon Fargate, as well as equivalent services on Azure and
Google Cloud Platform.

Note that we do not recommend running Cordaptor in Docker where your Corda node is running on the host machine,
because Docker containers are running in its own network and accessing services from localhost requires
cumbersome configuration, which is highly environment-specific. Running Cordaptor in Docker with a remote
Corda node is perfectly fine and fully supported.

If you want to use Cordaptor with a test Corda network runnnig on your machine,
we recommend using docker-compose to run both the nodes, and the Cordaptor.
This is fully compatible with Cordformation plugin (aka `deployNodes` Gradle task),
but requires slight tweaks to nodes configurations.

We provide well-documented reference configuration for Cordaptor and Corda network in `reference-cordapp` module
using docker-compose:
* [build.gradle](../reference-cordapp/build.gradle) compose-friendly `deployNodes` task definition.
* [compose-corda-network.yml](../reference-cordapp/compose-corda-network.yml)
  docker-compose configuration for a basic Corda network.
* [compose-cordaptor.yml](../reference-cordapp/compose-cordaptor.yml)
  docker-compose configuration for standalone containerized Cordaptor, intended to be used
  alongside `compose-corda-network.yml`.

Once you made necessary changes to the yml and `build.gradle` files and bootstrapped your Corda network using
Cordformation (`deployNodes` task), run the network alongside a standalone instance of Cordaptor using
the following command:  
  `docker-compose -f ./compose-corda-network.yml -f ./compose-cordaptor.yml up`

## Next steps

* Read about [how to use Cordaptor](./how-to-use.md) for more details


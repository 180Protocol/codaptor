# Developer Guide

## Automated Building (Corda version 4.4-4.7)

By using the included `automation.sh` file in the root directory you could automatically build and test the Cordaptor through Corda versions 4.4 to 4.7
(This could be changed by changing the `configurations` variable found inside the `automation.sh` file).

Automation.sh could accept 2 arguments:
###Argument #1
A first argument could be passed to build a specific version of the cordaptor. The example below will automatically build the cordaptor on Corda version 4.5.
#### Example
```
./automation.sh 4.5
```
###Argument #2
The `dockerPush` argument could be added which enables the shell script's ability to push the docker build directly to the remote repository. 

Note: You have to pass `""` on the first argument if you want the default to build all Corda Versions supported.
#### Example
```
./automation.sh "" dockerPush
```

### Step to run a docker compose network with custom cordapp & debug along with cordaptor
 
1. You need to run build command to build your corda app which generate build folder. After that you have run deployNodes command to generate nodes folder inside build folder.
2. You have to change below command to runnodes.sh file which is located inside build/nodes folder.

 "-Dcapsule.jvm.args="-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005" -jar corda.jar --logging-level=DEBUG"   

 i. --logging-level=DEBUG command used for enable logging.

3. Mount corda.jar and runnodes.sh file to your docker container volumes which are described below.
   volumes: 
    - ./build/nodes/ParticipantA/corda.jar:/opt/corda/corda.jar
    - ./build/nodes/runnodes:/opt/corda/runnodes
4. Bind debug port on docker network ports property.
   ports:
    - "6005:5005"
5. Finally, add Remote JVM Debug to debug your app.
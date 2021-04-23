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

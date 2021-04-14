# Developer Guide

## Automated Building (Corda version 4.5-4.7)

By using the included `automation.sh` file in the root directory you could automatically build and test the Cordaptor through Corda versions 4.5 to 4.7
(This could be changed by changing the `configurations` variable found inside the `automation.sh` file).
The `dockerPush` argument could be added which enables the shell scripts ability to push the docker build directly to the remote repository.
### Example
```
./automation.sh dockerPush
```

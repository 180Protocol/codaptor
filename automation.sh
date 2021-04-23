#Variable that houses the list of supported Corda Version(left) alongside its Corda platform version(right) counterpart
declare -A supportedCordaVersions=(
  [4.4]=6
  [4.5]=7
  [4.6]=8
  [4.7]=9
)

#Identifies the version/s of corda to be built and tested
if [ "$1" == "" ]; then
configurations=("${!supportedCordaVersions[@]}")
elif [[ "${!supportedCordaVersions[*]}" =~ $1 ]]; then
configurations=$1
else
echo "Version entered not supported"
read junk
  exit 1
fi

for configuration in "${configurations[@]}"; do

  echo -e "\033[0;31mBuilding Cordaptor Version $configuration"

  #Replaces version of Corda in gradle.properties file
  sed -i "/corda_core_release_version/c\corda_core_release_version=$configuration" ./gradle.properties
  sed -i "/corda_release_version/c\corda_release_version=$configuration" ./gradle.properties
  sed -i "/cordaptor_version/c\cordaptor_version=0.2.0-corda$configuration-SNAPSHOT" ./gradle.properties

  #Replaces target and minimum platform version in build.gradle under reference-cordapp project
  sed -i "/targetPlatformVersion/c\ \ttargetPlatformVersion ${supportedCordaVersions[$configuration]}" ./reference-cordapp/build.gradle
  sed -i "/minimumPlatformVersion/c\ \tminimumPlatformVersion ${supportedCordaVersions[$configuration]}" ./reference-cordapp/build.gradle

  #Build with imported configurations
  ./gradlew build

  #To make sure that all gradle processes are stopped before proceeding on next task
  ./gradlew --stop

  #Gradle task to create and push docker image of current Corda Version
  if [ "$2" == "dockerPush" ]; then
    ./gradlew :tar:dockerPush
  else
    ./gradlew :tar:docker
  fi

  #Task for getting cordaptor embedded jar and test results
  ./gradlew automatedResults
done

#Used to prompt the user to press "Enter" before exiting the terminal
echo -e "\033[0;31mAutomated Building Done!"
read junk

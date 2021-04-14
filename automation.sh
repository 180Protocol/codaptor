#Get list of configurations in Configurations file
configurations=("4.5" "4.6" "4.7")

for configuration in "${configurations[@]}"; do

  #If-Else statement to check tag the platform version based on corda version
  if [ "$configuration" == "4.5" ]; then
      platform_version=7;
  elif [ "$configuration" == "4.6" ]; then
      platform_version=8;
  elif [ "$configuration" == "4.7" ]; then
      platform_version=9;
  fi

  #Replaces version of Corda in gradle.properties file
  sed -i "/corda_core_release_version/c\corda_core_release_version=$configuration" ./gradle.properties
  sed -i "/corda_release_version/c\corda_release_version=$configuration" ./gradle.properties
  sed -i "/cordaptor_version/c\cordaptor_version=0.2.0-corda$configuration-SNAPSHOT" ./gradle.properties

  #Replaces target and minimum platform version in build.gradle under reference-cordapp project
  sed -i "/targetPlatformVersion/c\ \ttargetPlatformVersion $platform_version" ./reference-cordapp/build.gradle
  sed -i "/minimumPlatformVersion/c\ \tminimumPlatformVersion $platform_version" ./reference-cordapp/build.gradle


  #Build with imported configurations
  ./gradlew build

  #To make sure that all gradle processes are stopped before proceeding on next task
  ./gradlew --stop

  #Gradle task to create and push docker image of current Corda Version
  if [ "$1" == "dockerPush" ]; then
    ./gradlew :tar:dockerPush
  else
    ./gradlew :tar:docker
  fi

  #Task for getting cordaptor embedded jar and test results
  ./gradlew automatedResults
done

#Used to prompt the user to press "Enter" before exiting the terminal
read junk
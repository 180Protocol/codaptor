#Get list of configurations in Configurations file
configurations=$(ls configurations)

for configuration in $configurations; do

  #Import configurations from the configurations folder to the root build directory
  cp configurations/"$configuration"gradle.properties gradle.properties
  cp configurations/"$configuration"buildFile reference-cordapp/build.gradle

  #Build with imported configurations
  ./gradlew build

  #To make sure that all gradle processes are stopped before proceeding on next task
  ./gradlew --stop

  #Gradle task to create and push docker image of current Corda Version
  ./gradlew :tar:dockerPush

  #Task for getting cordaptor embedded jar and test results
  ./gradlew automatedResults
done

#Used to prompt the user to press "Enter" before exiting the terminal
read junk
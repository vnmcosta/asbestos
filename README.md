The Asbestos project - extending XDS Toolkit to support the FHIR standard.

Asbestos defines a micro-service environment to support the combined testing of the IHE XDS collection of 
profiles as well as the FHIR-based profiles. When complete, all the necessary components will be pulled into a Docker
environment to create a consolidated runtime. For now this is a work-in-progress.

For now all the components are part of the repository except for HAPI FHIR which is brought in
as a git submodule.

# To clone from github

    git clone https://github.com/usnistgov/asbestos.git 
    
to pull shell of project. This will create directory asbestos

    cd asbestos
    
next you will need the Javascript utilities if they are not already present (this shows what I did on Ubuntu - YMMV)

Install Yarn
    
    curl -sS https://dl.yarnpkg.com/debian/pubkey.gpg | sudo apt-key add -
    echo "deb https://dl.yarnpkg.com/debian/ stable main" | sudo tee /etc/apt/sources.list.d/yarn.list
    sudo apt-get update && sudo apt-get install yarn
    sudo apt-get update && sudo apt-get install --no-install-recommends yarn
    
verify 
 
     yarn --version
     
Add Vue utilities

    sudo yarn global add @vue/cli
    
to update the Javascript dependencies. These can be run at any time to refresh the Javascript libraries. Then run:

    cd asbestos-view
    npm init
    npm install
    
in a terminal to start UI in development mode. Do this from the view directory.

    cd asbestos-view
    npm run serve
    
also, to make sure all packages are up to date

    npm update
    
In IntelliJ, choose ECMAScript 6.

To build Run/Debug Configuration in IntelliJ

    Create configuration based on Tomcat Server/Local
    Select Application Server already on system
    Unselect Open Browser after launch
    URL should be http://localhost:8081/asbestos/
    VM options: -DEXTERNAL_CACHE=/home/bill/ec  (your milage will vary here)
    On the Deployment tab - select asbestos-war:war exploded and set Application context to /asbestos
    
# Development environment
    
See https://github.com/usnistgov/asbestos/wiki/Development-Environment
    
# Special builds

These are shell scripts in the root of the project. The top release building script is build-zip-release.sh
which calls the rest to do their part. The end result is the file /opt/asbestos.zip which is ready for release.

install-local.sh - asbestos and asbestos-assembly have been built.  This script 
                   installs asbestos-assembly in /opt/asbestos then 
                   adds xdstoolkit and
                   hapi fhir to the package in the correct places.
fhir.zip and the xdstools project must be alongside asbestos in the common directory:

    .
        fhir.zip
        asbestos/
        toolkit2/
                   
build-local-release.sh - Build asbestos and then asbestos-assembly.  Install asbestos-assembly (the file
structure of the release) and then add xdstools and hapi to the correct directories. Uses install-local.sh.

rebuild-local-release.sh - deletes current release as /opt/asbestos and calls build-local-release.sh to rebuild it.

build-zip-release.sh - relies on rebuild-local-release.sh to build up release content in /opt/asbestos. This
script then packages that directory as /opt/asbestos.zip which is ready for release.


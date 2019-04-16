The Asbestos project - extending XDS Toolkit to support the FHIR standard.

Asbestos defines a micro-service environment to support the combined testing of the IHE XDS collection of 
profiles as well as the FHIR-based profiles. When complete, all the necessary components will be pulled into a Docker
environment to create a consolidated runtime. For now this is a work-in-progress.

The asbestos components are connected, as far as the development environment is concerned, as a collection of GIT
subcomponents.  These directions show how to pull all the components into the development environment, usually IntelliJ Idea.

# To clone from github

    git clone https://github.com/usnistgov/asbestos.git 

to pull shell of project. This will create directory asbestos

    cd asbestos
    git submodule update --recursive --remote --init
    
to pull all the submodules (parts of build that come from separate github repositories)

Commits from IntelliJ will naturally go to the correct repository

If this is the first time running on this box in development

    cd view
    npm init
    npm install

to update the Javascript dependencies. These can be run at any time to refresh the Javascript libraries. Then run:

    npm run serve
    
in a terminal to start UI in development mode. Do this from the view directory.

# Project Organization

The following GitHub repositories are pulled in...

**asbestos-view** - the UI (https://github.com/usnistgov/asbestos-view.git) 

**asbestos-test-editor-service** -  test editor (https://github.com/usnistgov/asbestos-test-editor-service.git)

**asbestos-simapi** - API for linking to XDS Toolkit where simulators are managed (https://github.com/usnistgov/asbestos-simapi)

**asbestos-adapter** - adaptor necessary for reusing some XDS Toolkit code (https://github.com/usnistgov/asbestos-adapter)



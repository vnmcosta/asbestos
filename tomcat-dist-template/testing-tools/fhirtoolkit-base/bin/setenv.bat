set TESTING_TOOLS=%CATALINA_HOME%\testing-tools
set "CATALINA_OPTS=%CATALINA_OPTS% -DSERVICE_PROPERTIES=%TESTING_TOOLS%\service.properties"
REM External Cache: If an alternate External Cache is desired, please use update the -DEXTERNAL_CACHE Java System Property below.
set "CATALINA_OPTS=%CATALINA_OPTS% -DEXTERNAL_CACHE=%TESTING_TOOLS%\external_cache"


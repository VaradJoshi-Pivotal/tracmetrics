#!/bin/sh

export CLASSPATH=.:./apache-xmlrpc-3.1.2/lib/ws-commons-util-1.0.2.jar:./apache-xmlrpc-3.1.2/lib/commons-logging-1.1.jar:./apache-xmlrpc-3.1.2/lib/xmlrpc-client-3.1.2.jar:./apache-xmlrpc-3.1.2/lib/xmlrpc-common-3.1.2.jar:./apache-xmlrpc-3.1.2/lib/xmlrpc-server-3.1.2.jar:./tracdrops-read-only/target/javatrac-1.0-SNAPSHOT.jar:$GEMFIREXD_LOCATION/lib/gemfirexd-client.jar

# jssecacerts needs to be in the security directory of the JRE that we pick up
export PATH="$MYJRE/bin:$PATH"

java TracMetrics


tracmetrics
===========

Application to get snapshots from Trac and store them in Gemfire XD

Accessing Trac from Java application: 
When working on a client that works with an SSL enabled server running in https protocol, 
you could get error 'unable to find valid certification path to requested target' if the server certificate
is not issued by certification authority, but a self signed or issued by a private CMS.

To work around this problem add the server certificate to your trusted Java key store.

The way to do it:
  Compile the included InstallCert.java
  Run InstallCert for the server as:
    java InstallCert https://MYTRACSERVER.MYDOMAIN.com/
  This will produce a file called jssecacerts in your current directory.
  To use it in your program, either configure JSSE to use it as its trust store or
  copy it into your $JAVA_HOME/jre/lib/security directory. If you want all Java applications
  to recognize the certificate as trusted and not just JSSE, you could also overwrite
  the cacerts file in that directory.
  
Download xmlrpc distribution from:
http://mirrors.ibiblio.org/maven2/org/apache/xmlrpc/xmlrpc-dist/3.1.2/
Or use the one included in this repo.

To build tracdrops-read-only:
  cd tracdrops-read-only
  mvn install -Dmaven.test.skip=true
  This will create javatrac-1.0-SNAPSHOT.jar in the target directory.

Start a Gemfire XD instance and load the trac schema in the file 'create_trac_schema.sql'.
There is sample data in 'loadTracData.sql' that you can also load.

To compile and run TracMetrics.java:

export GEMFIREXD_LOCATION="YOUR_GEMFIREXD_LOCATION"
export CLASSPATH=.:./apache-xmlrpc-3.1.2/lib/ws-commons-util-1.0.2.jar:./apache-xmlrpc-3.1.2/lib/commons-logging-1.1.jar:./apache-xmlrpc-3.1.2/lib/xmlrpc-client-3.1.2.jar:./apache-xmlrpc-3.1.2/lib/xmlrpc-common-3.1.2.jar:./apache-xmlrpc-3.1.2/lib/xmlrpc-server-3.1.2.jar:./tracdrops-read-only/target/javatrac-1.0-SNAPSHOT.jar:$GEMFIREXD_LOCATION/lib/gemfirexd-client.jar

# jssecacerts needs to be in the security directory of the JRE that we pick up
export PATH="$JAVA_HOME/bin:$PATH"
javac TracMetrics.java
java TracMetrics

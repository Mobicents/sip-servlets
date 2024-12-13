Get started
-----------

Start the sip servlets container from bin directory with ./run.bat run or ./run.sh command.
You should see a bunch of stuff on the console saying that some sip servlet applications have been added.
Now that you can start it, you can play with the examples bundled with the release. For more information,
check this link out http://www.mobicents.org/examples.html

There is an Application Router Management Console at this location http://localhost:8080/sip-servlets-management.
This application will help you to more easily configure the default application router.

There is an Enterprise Monitoring and Management Console at this location http://localhost:8080/admin-console
This application will help you to see metrics and control the different settings of the server (user = admin, password = admin)

This package includes Mobicents Media Server to allow developers to create complete SIP/JEE/Web/Media
converged applications. For more information about Media Server visit http://www.mobicents.org/mms/mms-main.html

The Media Server is packaged separately at the top level of this distribution in mobicents-media-server and should be started in a different JVM than the Mobicents Sip Servlets one through 
sh ./mobicents-media-server/bin/run.sh

You can find a real example demonstrating Sip Servlets, JEE, JBoss Seam, Media Server, Text to Speech and jBPM working together here http://www.mobicents.org/shopping-demo.html.

The default port for SIP is 5080. There is two predeployed sample application :
- click2call : You can navigate to http://localhost:8080/click2call in order to see it in action. See http://www.mobicents.org/click2call.html for more information
- diameter-event-charging : a Diameter Event Charging service based on Location Service that performs call charging at a fixed-rate (event charging). See http://www.mobicents.org/diameter_event_charging.html for more information

Clustering for JBoss
--------------------

This distribution includes a pre-configured cluster-enabled server. It is located in the 'all' configuration.
Before starting the cluster-enabled server you must have the Sip Load Balancer from the sip-balancer 
directory running on your machine or on the network.

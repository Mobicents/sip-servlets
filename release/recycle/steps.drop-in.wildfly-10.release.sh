#!/bin/sh -e
BUILD_DIR=./build/release/target/restcomm-sip-servlets-as10
CHECKOUT_DIR=./build/release/target/dependencies
#
# set AS10_TAG as name of the AS10 release/tag
AS10_FINAL_NAME=wildfly-10.0.0.Final
#AS10_NAME=wildfly-10.0.0.Final
#AS10_TAG=10.0.0.Final	
VERSION=$1
MSS_FINAL_NAME=restcomm-sip-servlets-$VERSION-$AS10_FINAL_NAME

rm -rf $BUILD_DIR
mkdir -p $BUILD_DIR
rm -rf $CHECKOUT_DIR
mkdir -p $CHECKOUT_DIR

## get tag and build to have maven deps installed in the local maven repo
#git clone https://github.com/jbossas/jboss-as.git $CHECKOUT_DIR/jboss-as
#cd $CHECKOUT_DIR/jboss-as
#git checkout $AS10_TAG
#mvn clean install -DskipTests

# get JBoss AS 10 release 
cd $CHECKOUT_DIR
wget http://download.jboss.org/wildfly/10.0.0.Final/wildfly-10.0.0.Final.zip 
unzip wildfly-10.0.0.Final.zip

cd ../../../..
mv $CHECKOUT_DIR/$AS10_FINAL_NAME $BUILD_DIR
mv $BUILD_DIR/$AS10_FINAL_NAME $BUILD_DIR/$MSS_FINAL_NAME

# build sip-servlets-as10-drop-in modules and install in AS10
mvn clean install -Pas10

# modules installation
cp -vpr ./containers/sip-servlets-as10-drop-in/build-restcomm-modules/target/$AS10_FINAL_NAME/modules/system/layers/base/org/mobicents $BUILD_DIR/$MSS_FINAL_NAME/modules/system/layers/base/org/mobicents
mvn clean install war:inplace -f ./sip-servlets-examples/click-to-call/pom.xml
cp -pr ./sip-servlets-examples/click-to-call/target/click-to-call-servlet-*.war $BUILD_DIR/$MSS_FINAL_NAME/standalone/deployments/click2call.war
mvn clean install war:inplace -f ./sip-servlets-examples/websocket-b2bua/pom.xml
cp -pr ./sip-servlets-examples/websocket-b2bua/target/websockets-sip-servlet-*.war $BUILD_DIR/$MSS_FINAL_NAME/standalone/deployments/websockets-sip-servlet.war
mvn clean install war:inplace -f ./sip-servlets-examples/media-jsr309-servlet/pom.xml
cp -pr ./sip-servlets-examples/media-jsr309-servlet/target/media-jsr309-servlet.war $BUILD_DIR/$MSS_FINAL_NAME/standalone/deployments/media-jsr309-servlet.war
mvn clean install war:inplace -f ./management/sip-servlets-management/pom.xml
cp -pr ./management/sip-servlets-management/target/sip-servlets-management.war $BUILD_DIR/$MSS_FINAL_NAME/standalone/deployments/sip-servlets-management.war
wget --no-check-certificate http://labs.consol.de/maven/repository/org/jolokia/jolokia-war/1.1.0/jolokia-war-1.1.0.war -O  $BUILD_DIR/$MSS_FINAL_NAME/standalone/deployments/jolokia.war
#read -n1 -r -p "Press space to continue..." key
mkdir -p $BUILD_DIR/$MSS_FINAL_NAME/standalone/configuration/dars
mkdir -p $BUILD_DIR/$MSS_FINAL_NAME/domain/configuration/dars
cp -vprf $BUILD_DIR/../../../../sip-servlets-examples/websocket-b2bua/websocket-dar.properties $BUILD_DIR/$MSS_FINAL_NAME/standalone/configuration/dars/mobicents-dar.properties
cp -vprf $BUILD_DIR/../../../../sip-servlets-examples/websocket-b2bua/websocket-dar.properties $BUILD_DIR/$MSS_FINAL_NAME/domain/configuration/dars/mobicents-dar.properties
cp -vpr $BUILD_DIR/../../mss-sip-stack.properties $BUILD_DIR/$MSS_FINAL_NAME/standalone/configuration/
cp -vpr $BUILD_DIR/../../mss-sip-stack.properties $BUILD_DIR/$MSS_FINAL_NAME/domain/configuration/
#Copy conf settings for standalone and domain profiles
cp -vprf $BUILD_DIR/../../wildfly-10-standalone-conf $BUILD_DIR/$MSS_FINAL_NAME/bin/standalone.conf
#cp -vpr $BUILD_DIR/../../as10-domain-conf $BUILD_DIR/$MSS_FINAL_NAME/bin/domain.conf

cd $BUILD_DIR/$MSS_FINAL_NAME

# Create standalone-sip.xml file
#cp ./standalone/configuration/standalone.xml ./standalone/configuration/standalone-sip.xml
cp -vpr ../../../../../containers/sip-servlets-as10-drop-in/jboss-as-restcomm/standalone-sip.xml ./standalone/configuration/standalone-sip.xml

#patch -p0 --verbose < ../../../../../containers/sip-servlets-as10-drop-in/patches/patch.7.2.0.Final.standalone.sip.dropin.xml
#patch -p0 --verbose < ../../../../../containers/sip-servlets-as10-drop-in/patches/patch.domain.sip.dropin.xml

#
# Configure jboss-as-web module
#patch -p0 --verbose < ../../../../../containers/sip-servlets-as10-drop-in/patches/patch.7.2.0.Final.jboss-as-web.module.xml

#
# Configure jboss-as-ee module
#patch -p0 --verbose < ../../../../../containers/sip-servlets-as10-drop-in/patches/patch.7.2.0.Final.jboss-as-ee.module.xml

cd ..


ant extract-mms -f ../../build.xml -Dmss.home=./target/restcomm-sip-servlets-as10/$MSS_FINAL_NAME
#ant build-mobicents-sip-load-balancer -f ../../build.xml -Dmss.home=./target/mss-as10/$MSS_FINAL_NAME

zip -r $MSS_FINAL_NAME.zip $MSS_FINAL_NAME

#
# Run AS10
#export JBOSS_HOME=$BUILD_DIR/$AS10_TAG
#./bin/standalone.sh -c standalone-sip.xml -Djavax.servlet.sip.dar=file:///tmp/mobicents-dar.properties

# Teiresis
This is a simple UDP messaging framework. Just include and start publishing and receiving.

All apps including Teiresias running on any machine in your network will know each other. Clients establish flyweight 'connections' and propagate messages they receive to known peers.

Note that this is not intended to be used for any production purposes. Yet, if you're searching for an extremely quick way of establishing some sort of cloud messaging between your apps, see below for Usage.

## Building
Build the framework: `mvn clean compile assembly:single`

## Usage
Include the built jar file into your project and call `Teiresis.init(...)` and provide a messageCallback. Publish messages via the instance method `publish()`.

This is all you need to do to enable messaging.

Please see the Javadoc for further documentation.

# Have fun!
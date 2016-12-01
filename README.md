# maven-filtering-default-value
a jar dependency for maven-resources-plugin

# function

if there's a property in pom.xml like this:

			<properties>
				<key>value</key>
			</properties>

maven can filtering the resources file by the placehoder:

		${key} or @key@

if there's no property key set in pom.xml, the placehoder has no default value.

this jar can enhance the maven-resources-plugin build in maven-core to set a default value for the placeholder by format like this:
		
		${key?defaultValue} or @key?defaultValue@

# usage
the version of maven-resources-plugin must be higher than 2.5

			<plugin>
				<groupId>org.apache.maven.plugins</groupId>
				<artifactId>maven-resources-plugin</artifactId>
				<version>3.0.1</version>
				<configuration>
					<mavenFilteringHints>
						<mavenFilteringHint>filtering-enhancer</mavenFilteringHint>
					</mavenFilteringHints>
				</configuration>
				<dependencies>
					<dependency>
						<groupId>com.netease.maven</groupId>
						<artifactId>maven-filtering-default-value</artifactId>
						<version>1.0.0</version>
					</dependency>
				</dependencies>
			</plugin>

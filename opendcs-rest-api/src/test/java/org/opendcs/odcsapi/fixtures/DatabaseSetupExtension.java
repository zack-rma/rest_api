/*
 *  Copyright 2024 OpenDCS Consortium and its Contributors
 *
 *  Licensed under the Apache License, Version 2.0 (the "License")
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.opendcs.odcsapi.fixtures;

import javax.servlet.http.HttpServletResponse;

import decodes.db.DatabaseException;
import decodes.db.ScheduleEntry;
import decodes.db.ScheduleEntryStatus;
import decodes.polling.DacqEvent;
import decodes.sql.DbKey;
import io.restassured.RestAssured;
import opendcs.dai.DacqEventDAI;
import opendcs.dai.ScheduleEntryDAI;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.PreconditionViolationException;
import org.opendcs.fixtures.configuration.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables;
import uk.org.webcompere.systemstubs.properties.SystemProperties;
import uk.org.webcompere.systemstubs.security.SystemExit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

public class DatabaseSetupExtension implements BeforeEachCallback
{
	private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseSetupExtension.class);
	private static DbType currentDbType;
	private static TomcatServer currentTomcat;
	private final Configuration config;
	private static Configuration currentConfig;
	private final DbType dbType;
	private TomcatServer tomcatServer;

	public DatabaseSetupExtension(Configuration config, DbType dbType)
	{
		this.config = config;
		this.dbType = dbType;
		currentConfig = config;
	}

	public static DbType getCurrentDbType()
	{
		return currentDbType;
	}

	public static TomcatServer getCurrentTomcat()
	{
		return currentTomcat;
	}

	@Override
	public void beforeEach(ExtensionContext context) throws Exception
	{
		String warContext = System.getProperty("warContext", "odcsapi");
		if(tomcatServer == null)
		{
			tomcatServer = startTomcat(warContext);
		}
		RestAssured.baseURI = "http://localhost";
		RestAssured.port = tomcatServer.getPort();
		RestAssured.basePath = warContext;
		currentDbType = dbType;
		currentTomcat = tomcatServer;
	}

	private TomcatServer startTomcat(String warContext) throws Exception
	{
		SystemExit exit = new SystemExit();
		EnvironmentVariables environment = new EnvironmentVariables();
		SystemProperties properties = new SystemProperties();
		config.start(exit, environment, properties);
		environment.getVariables().forEach(System::setProperty);
		if(dbType == DbType.CWMS)
		{
			System.setProperty("DB_DRIVER_CLASS", "oracle.jdbc.driver.OracleDriver");
		}
		else
		{
			System.setProperty("DB_DRIVER_CLASS", "org.postgresql.Driver");
		}
		TomcatServer tomcat = new TomcatServer("build/tomcat", 0, warContext);
		tomcat.start();
		RestAssured.baseURI = "http://localhost";
		RestAssured.port = tomcat.getPort();
		RestAssured.basePath = warContext;
		healthCheck();
		return tomcat;
	}

	private static void healthCheck() throws InterruptedException
	{
		int attempts = 0;
		int maxAttempts = 15;
		for(; attempts < maxAttempts; attempts++)
		{
			try
			{
				given()
						.when()
						.delete("/logout")
						.then()
						.assertThat()
						.statusCode(is(HttpServletResponse.SC_NO_CONTENT));
				LOGGER.atInfo().log("Server is up!");
				break;
			}
			catch(Throwable e)
			{
				LOGGER.atInfo().log("Waiting for the server to start...");
				Thread.sleep(100);//NOSONAR
			}
		}
		if(attempts == maxAttempts)
		{
			throw new PreconditionViolationException("Server didn't start in time...");
		}
	}

	public static void storeScheduleEntryStatus(ScheduleEntryStatus status) throws DatabaseException
	{
		try (ScheduleEntryDAI dai = currentConfig.getTsdb().makeScheduleEntryDAO())
		{
			dai.writeScheduleStatus(status);
		}
		catch(Throwable e)
		{
			throw new DatabaseException("Unable to store schedule entry status ", e);
		}
	}

	public static void deleteScheduleEntryStatus(DbKey scheduleEntryId) throws DatabaseException
	{
		try (ScheduleEntryDAI dai = currentConfig.getTsdb().makeScheduleEntryDAO())
		{
			dai.deleteScheduleStatusFor(new ScheduleEntry(scheduleEntryId));
		}
		catch(Throwable e)
		{
			throw new DatabaseException("Unable to delete schedule entry status for specified schedule entry", e);
		}
	}

	public static void storeDacqEvent(DacqEvent event) throws DatabaseException
	{
		try (DacqEventDAI dai = currentConfig.getTsdb().makeDacqEventDAO())
		{
			dai.writeEvent(event);
		}
		catch(Throwable e)
		{
			throw new DatabaseException("Unable to store event", e);
		}
	}

	public static void deleteEventsForPlatform(DbKey platformId) throws DatabaseException
	{
		try (DacqEventDAI dai = currentConfig.getTsdb().makeDacqEventDAO())
		{
			dai.deleteEventsForPlatform(platformId);
		}
		catch(Throwable e)
		{
			throw new DatabaseException("Unable to delete events for specified platform", e);
		}
	}
}
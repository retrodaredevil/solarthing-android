package me.retrodaredevil.solarthing.android.request

import com.fasterxml.jackson.databind.node.ObjectNode
import me.retrodaredevil.couchdb.CouchProperties
import me.retrodaredevil.couchdb.CouchPropertiesBuilder
import me.retrodaredevil.couchdbjava.ViewQuery
import me.retrodaredevil.couchdbjava.ViewQueryParamsBuilder
import me.retrodaredevil.couchdbjava.exception.CouchDbException
import me.retrodaredevil.couchdbjava.json.jackson.CouchDbJacksonUtil
import me.retrodaredevil.solarthing.android.util.createCouchDbInstance
import me.retrodaredevil.solarthing.packets.collection.PacketGroup
import me.retrodaredevil.solarthing.packets.collection.parsing.PacketGroupParser
import me.retrodaredevil.solarthing.packets.collection.parsing.PacketParseException
import java.io.PrintWriter
import java.io.StringWriter
import java.util.*


class CouchDbDataRequester(
    private val connectionPropertiesCreator: () -> CouchProperties,
    private val databaseName: String,
    private val packetGroupParser: PacketGroupParser,
    private val startKeyGetter: () -> Long = { System.currentTimeMillis() - 2 * 60 * 60 * 1000 }
) : DataRequester {

    @Volatile
    override var currentlyUpdating = false
        private set

    /**
     * Takes over the current thread and runs until data is retrieved or an error occurred.
     *
     * @throws IllegalStateException Thrown when [currentlyUpdating] is true
     * @return The [DataRequest] which holds data about if the request was successful or not
     */
    override fun requestData(): DataRequest {
        synchronized(this) {
            if (currentlyUpdating) {
                throw IllegalStateException("The data is currently being updated!")
            }
            currentlyUpdating = true
        }
        val couchProperties: CouchProperties = connectionPropertiesCreator()
        val instance = createCouchDbInstance(couchProperties)
        try {
            val database = instance.getDatabase(databaseName)

            val viewQuery = ViewQuery(
                    "packets", "millis",
                    ViewQueryParamsBuilder()
                            .startKey(startKeyGetter())
                            .build()
            )
            val result = database.queryView(viewQuery)
//            println("result=$result")
            val list = ArrayList<PacketGroup>()
            var exception: Exception? = null
            for (row in result.rows) {
                val objectNode = CouchDbJacksonUtil.getNodeFrom(row.value) as ObjectNode
                try {
                    val packetGroup = packetGroupParser.parse(objectNode)
                    list.add(packetGroup)
                } catch(ex: PacketParseException){
                    exception = ex
                }
            }
            if(list.isEmpty() && exception != null){
                return DataRequest(
                    emptyList(),
                    false, "Got all unknown packets",
                    couchProperties.host,
                    getStackTrace(exception),
                    exception.message,
                    getAuthDebug(couchProperties)
                )
            }
            exception?.printStackTrace()
//            println("Updated collections!")
            return DataRequest(list, true, "Request Successful", couchProperties.host, authDebug = getAuthDebug(couchProperties))
        } catch(ex: CouchDbException){
            ex.printStackTrace()
            return DataRequest(Collections.emptyList(), false,
                "Request Failed", couchProperties?.host, getStackTrace(ex), ex.message, getAuthDebug(couchProperties))
        } catch(ex: NullPointerException){
            ex.printStackTrace()
            return DataRequest(Collections.emptyList(), false,
                "(Please report) NPE (Likely Parsing Error)", couchProperties?.host, getStackTrace(ex), ex.message, getAuthDebug(couchProperties))
        } catch(ex: Exception) {
            ex.printStackTrace()
            return DataRequest(Collections.emptyList(), false,
                "(Please report) ${ex.javaClass.simpleName} (Unknown)", couchProperties?.host, getStackTrace(ex), ex.message, getAuthDebug(couchProperties))
        } finally {
            currentlyUpdating = false
        }
    }
    private fun getAuthDebug(couchProperties: CouchProperties?): String?{
        return if(couchProperties!= null)
            "Properties: ${couchProperties.protocol} ${couchProperties.host}:${couchProperties.port} ${couchProperties.username} $databaseName\n"
            else null
    }
    private fun getStackTrace(throwable: Throwable): String{
        val stringWriter = StringWriter()
        val printWriter = PrintWriter(stringWriter)
        throwable.printStackTrace(printWriter)
        return stringWriter.toString()
    }
}

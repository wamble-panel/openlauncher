package com.openlauncher.app.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Runs the Kotlin reader and planner on a graph built by tools/map/build_routing_graph.py
 * from routing/test.osm: Tahrir Street (east-west, 60 km/h) crossed by Nile Road at
 * 31.01, a one-way street, and a small roundabout at the east end.
 */
class RoutePlannerTest {
    private val graph: RoutingGraph = run {
        val file = File(javaClass.classLoader!!.getResource("routing/test.graph")!!.toURI())
        assertNotNull(RoutingGraph.open(file))
        RoutingGraph.open(file)!!
    }

    @Test
    fun snapsToNearestRoadWithNames() {
        val snap = graph.snap(30.0001, 31.003)
        assertNotNull(snap)
        val names = graph.names(snap!!.useg)
        assertEquals("Tahrir Street", names.display)
        assertEquals("شارع التحرير", names.local)
        assertEquals(60, graph.maxspeed(snap.useg))
        assertTrue(snap.distanceM < 20.0)
        assertNull(graph.snap(30.05, 31.05))
    }

    @Test
    fun plansLeftTurnOntoNileRoad() {
        val from = graph.snap(30.0, 31.001, 90f)!!
        val to = graph.snap(30.0095, 31.01)!!
        val route = RoutePlanner(graph).plan(from, to, 90f)
        assertNotNull(route)
        route!!
        assertEquals(1924.0, route.lengthM, 60.0)
        val types = route.maneuvers.map { it.type }
        assertEquals(ManeuverType.ARRIVE, types.last())
        val left = route.maneuvers.first { it.type == ManeuverType.LEFT }
        assertEquals("Nile Road", left.road.display)
        assertEquals(867.0, left.alongM, 40.0)
        assertEquals("Turn left onto Nile Road", left.text)
        assertTrue(route.durationS > 0)
        assertEquals(route.lat.size, route.cumDist.size)
    }

    @Test
    fun angleHelpers() {
        assertEquals(90f, bearing(30.0, 31.0, 30.0, 31.01), 0.5f)
        assertEquals(20f, angleDiff(350f, 10f), 0.001f)
    }
}

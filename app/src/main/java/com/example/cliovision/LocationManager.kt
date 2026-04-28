package com.example.cliovision

import android.content.Context
import android.annotation.SuppressLint
import android.location.Location
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

data class CampusLocation(
    val buildingId: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val yearBuilt: String,
    val description: String,
    val folderPath: String,
    val radiusMeters: Float = 30f
)

object CampusLocationManager {

    // ── Building data loaded from Excel ───────────────────────────────────
    private val _campusLocations = MutableStateFlow<List<CampusLocation>>(emptyList())
    val campusLocations: StateFlow<List<CampusLocation>> = _campusLocations

    // ── Current location state ─────────────────────────────────────────────
    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation

    private val _nearestLocation = MutableStateFlow<CampusLocation?>(null)
    val nearestLocation: StateFlow<CampusLocation?> = _nearestLocation

    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null

    private val locationHandlerThread = android.os.HandlerThread("LocationProvider")
        .apply { start() }


    // ── Load buildings from Excel ──────────────────────────────────────────
    suspend fun loadBuildingsFromExcel(context: Context, fileName: String = "campus_buildings.xlsx") {
        withContext(Dispatchers.IO) {
            try {
                context.assets.open(fileName).use { inputStream ->
                    val workbook = XSSFWorkbook(inputStream)
                    val sheet = workbook.getSheetAt(0)
                    val buildings = mutableListOf<CampusLocation>()

                    // Skip header row (row 0), start from row 1
                    for (rowIndex in 1..sheet.lastRowNum) {
                        val row = sheet.getRow(rowIndex) ?: continue

                        // Helper to safely read cell values
                        fun cellString(col: Int): String {
                            val cell = row.getCell(col) ?: return ""
                            return when (cell.cellType) {
                                org.apache.poi.ss.usermodel.CellType.NUMERIC ->
                                    cell.numericCellValue.toString()

                                org.apache.poi.ss.usermodel.CellType.STRING ->
                                    cell.stringCellValue.trim()

                                else -> cell.toString().trim()
                            }
                        }

                        fun cellDouble(col: Int): Double {
                            val cell = row.getCell(col) ?: return 0.0
                            return try {
                                cell.numericCellValue
                            } catch (e: Exception) {
                                cell.stringCellValue.trim().toDoubleOrNull() ?: 0.0
                            }
                        }

                        // Read columns in order:
                        // 0: Building_ID, 1: Building_Name, 2: Latitude,
                        // 3: Longitude, 4: Year_Built, 5: Description, 6: Folder_Path
                        val building = CampusLocation(
                            buildingId = cellString(0),
                            name = cellString(1),
                            latitude = cellDouble(2),
                            longitude = cellDouble(3),
                            yearBuilt = cellString(4),
                            description = cellString(5),
                            folderPath = cellString(6)
                        )

                        // Skip rows with missing critical data
                        if (building.name.isNotEmpty() &&
                            building.latitude != 0.0 &&
                            building.longitude != 0.0
                        ) {
                            buildings.add(building)
                            Log.d("LocationManager", "Loaded: ${building.name}")
                        }
                    }

                    workbook.close()
                    _campusLocations.value = buildings
                    Log.d(
                        "LocationManager",
                        "Loaded ${buildings.size} buildings from $fileName"
                    )
                }
            } catch (e: Exception) {
                Log.e("LocationManager", "Failed to load Excel: ${e.message}")
            }
        }
    }

    // ── Start tracking location ────────────────────────────────────────────
    @SuppressLint("MissingPermission")
    fun startTracking(context: Context) {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            15000L
        ).apply {
            setMinUpdateIntervalMillis(10000L)
            setMaxUpdateDelayMillis(30000L)
        }.build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                val newNearest = findNearestLocation(location)

                if (newNearest?.buildingId != _nearestLocation.value?.buildingId) {
                    _nearestLocation.value = newNearest
                    Log.d("Location", "Moved near: ${newNearest?.name ?: "Unknown"}")
                }
                _currentLocation.value = location
            }
        }

        fusedLocationClient?.requestLocationUpdates(
            locationRequest,
            locationCallback!!,
            locationHandlerThread.looper
        )

        Log.d("Location", "Location tracking started")
    }

    fun stopTracking() {
        locationCallback?.let {
            fusedLocationClient?.removeLocationUpdates(it)
        }
        locationCallback = null
        Log.d("Location", "Location tracking stopped")
    }

    // ── Find nearest campus location ───────────────────────────────────────
    fun findNearestLocation(location: Location): CampusLocation? {
        val locations = _campusLocations.value
        if (locations.isEmpty()) return null

        return locations.minByOrNull { campusLoc ->
            val results = FloatArray(1)
            Location.distanceBetween(
                location.latitude, location.longitude,
                campusLoc.latitude, campusLoc.longitude,
                results
            )
            results[0]
        }
    }

    // ── Check if user is within range of a location ────────────────────────
    fun isNearLocation(location: Location, campusLoc: CampusLocation): Boolean {
        val results = FloatArray(1)
        Location.distanceBetween(
            location.latitude, location.longitude,
            campusLoc.latitude, campusLoc.longitude,
            results
        )
        return results[0] <= campusLoc.radiusMeters
    }

    // ── Get location context string for Gemini prompt ──────────────────────
    fun getLocationContext(location: Location?): String {
        if (location == null) return ""
        val nearest = findNearestLocation(location) ?: return ""

        val results = FloatArray(1)
        Location.distanceBetween(
            location.latitude, location.longitude,
            nearest.latitude, nearest.longitude,
            results
        )
        val distanceMeters = results[0].toInt()

        return if (distanceMeters <= nearest.radiusMeters) {
            buildString {
                append("The visitor is currently standing at ${nearest.name}.")
                if (nearest.yearBuilt.isNotEmpty()) {
                    append(" Built in ${nearest.yearBuilt}.")
                }
                append(" ${nearest.description}")
            }
        } else {
            "The visitor is approximately ${distanceMeters}m from ${nearest.name}."
        }
    }
}
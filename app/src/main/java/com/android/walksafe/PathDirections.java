package com.android.walksafe;

import static android.content.ContentValues.TAG;

import android.content.Context;
import android.graphics.Color;
import android.location.Location;
import android.os.AsyncTask;
import android.util.Log;

import com.google.android.gms.maps.CameraUpdate;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class PathDirections extends AsyncTask<Location, Void, List<List<LatLng>>> {

    private MapActivity mapActivity;
    private GoogleMap gMap;
    private Context context;
    private LatLng destination;
    private String destinationName;
    private boolean requestAlternativeRoutes; // Flag to request alternative routes
    private List<Polyline> polylines = new ArrayList<>(); // List to store all polylines
    private Polyline primaryPolyline; // Store reference to the primary polyline
    private List<List<LatLng>> decodedPolylines;
    private CrimeData crimeData;
    private CCTVData cctvData;
    private PoliceStationData policeStationData;
    private StreetlightData streetlightData;



    public PathDirections (MapActivity mapActivity, CCTVData cctvData, CrimeData crimeData, PoliceStationData policeStationData, StreetlightData streetlightData) {
        this.mapActivity = mapActivity;
        this.context = mapActivity.getApplicationContext();

        this.cctvData = cctvData;
        this.crimeData = crimeData;
        this.policeStationData = policeStationData;
        this.streetlightData = streetlightData;

        this.gMap = null;
    }

    public void setGoogleMap(GoogleMap googleMap) {
        this.gMap = googleMap;
    }

    public void setDestination(LatLng destination, String destinationName) {
        this.destination = destination;
        this.destinationName = destinationName;
    }

    public void setRequestAlternativeRoutes(boolean requestAlternativeRoutes) {
        this.requestAlternativeRoutes = requestAlternativeRoutes;
    }



    @Override
    protected List<List<LatLng>> doInBackground(Location... locations) {
        List<List<LatLng>> decodedPolylines = new ArrayList<>();
        if (destination == null) {
            return null;
        }

        Location origin = locations[0];
        LatLng originLatLng = new LatLng(origin.getLatitude(), origin.getLongitude());

        try {
            // Construct URL for the Routes API
            String apiKey = context.getString(R.string.api_key);
            String urlString = "https://maps.googleapis.com/maps/api/directions/json?" +
                    "origin=" + originLatLng.latitude + "," + originLatLng.longitude +
                    "&destination=" + destination.latitude + "," + destination.longitude +
                    "&mode=walking" +
                    "&key=" + apiKey;

            if (requestAlternativeRoutes) {
                urlString += "&alternatives=true"; // Add parameter to request alternative routes
            }

            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            InputStream inputStream = connection.getInputStream();
            Scanner scanner = new Scanner(inputStream);
            StringBuilder stringBuilder = new StringBuilder();

            while (scanner.hasNext()) {
                stringBuilder.append(scanner.nextLine());
            }

            JSONObject jsonObject = new JSONObject(stringBuilder.toString());
            JSONArray routesArray = jsonObject.getJSONArray("routes");

            for (int i = 0; i < routesArray.length(); i++) {
                JSONObject route = routesArray.getJSONObject(i);
                JSONObject overviewPolyline = route.getJSONObject("overview_polyline");
                String points = overviewPolyline.getString("points");
                List<LatLng> decodedPolyline = decodePolyline(points);
                decodedPolylines.add(decodedPolyline);
            }
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Error fetching route data", e);
        }

        return decodedPolylines;
    }



    // Inside onPostExecute method
    @Override
    protected void onPostExecute(List<List<LatLng>> decodedPolylines) {
        this.decodedPolylines = decodedPolylines; // Store decoded polylines for later use
        gMap.clear(); // Clear previous polylines from the map
        polylines.clear(); // Clear the list of polylines

        // Pass route data back to MapActivity
        if (mapActivity != null) {
            mapActivity.onRouteObtained(decodedPolylines.get(0)); // Pass only the primary polyline
        }

        for (int i = 0; i < decodedPolylines.size(); i++) { // Added loop counter
            List<LatLng> decodedPolyline = decodedPolylines.get(i); // Get current polyline
            if (!decodedPolyline.isEmpty()) {
                PolylineOptions polylineOptions = new PolylineOptions();
                polylineOptions.addAll(decodedPolyline);
                Polyline polyline;
                if (i == 0) {
                    polylineOptions.color(Color.parseColor("#1A73E8")); // Main route color is blue
                    polylineOptions.zIndex(1); // Set higher z-index for the primary polyline
                    polyline = gMap.addPolyline(polylineOptions);
                    primaryPolyline = polyline; // Store reference to the primary polyline
                } else {
                    polylineOptions.color(Color.parseColor("#7A7878")); // Set other polylines color to grey
                    polylineOptions.zIndex(0); // Set lower z-index for the alternative polylines
                    polyline = gMap.addPolyline(polylineOptions);
                }

                // Set click listener for polylines after adding them to the map
                polyline.setClickable(true);
                polyline.setTag("Distance: " + computePolylineDistance(decodedPolyline)); // Set tag for polyline
                polylines.add(polyline);

                // Add markers for origin and destination
                MarkerOptions originMarkerOptions = new MarkerOptions()
                        .position(decodedPolyline.get(0)) // First point of the polyline (origin)
                        .icon(BitmapDescriptorFactory.defaultMarker(210))
                        .title("Origin");
                MarkerOptions destinationMarkerOptions = new MarkerOptions()
                        .position(decodedPolyline.get(decodedPolyline.size() - 1)) // Last point of the polyline (destination)
                        .title(destinationName); // Use destinationName passed from autocompleteFragment
                gMap.addMarker(originMarkerOptions);
                gMap.addMarker(destinationMarkerOptions);

                // Calculate bounds for the route polyline
                LatLngBounds.Builder builder = new LatLngBounds.Builder();
                for (LatLng latLng : decodedPolyline) {
                    builder.include(latLng);
                }
                LatLngBounds bounds = builder.build();

                // Zoom out to encompass the entire route
                int padding = 150; // Padding in pixels
                CameraUpdate cameraUpdate = CameraUpdateFactory.newLatLngBounds(bounds, padding);
                gMap.animateCamera(cameraUpdate);
            }


            // Set click listener for polylines after adding them to the map
            GoogleMap.OnPolylineClickListener polylineClickListener = new GoogleMap.OnPolylineClickListener() {
                @Override
                public void onPolylineClick(Polyline clickedPolyline) {
                    for (Polyline line : polylines) {
                        if (line.equals(clickedPolyline)) {
                            line.setColor(Color.parseColor("#1A73E8")); // Set clicked polyline color to blue
                            line.setZIndex(1); // Set higher z-index for the clicked polyline
                        } else {
                            line.setColor(Color.parseColor("#7A7878")); // Set other polylines color to grey
                            line.setZIndex(0); // Set lower z-index for other polylines
                        }
                    }
                    zoomToPolyline(clickedPolyline); // Zoom to clicked polyline

                    // Fetch crime data when a new polyline is clicked
                    crimeData.fetchCrimeData(clickedPolyline.getPoints(), new CrimeData.CrimeDataCallback() {
                        @Override
                        public void onCrimeDataReceived(int count) {
                            mapActivity.updateBottomSheetCrimeCount(count);
                        }
                    });

                    // Fetch cctv data when a new polyline is clicked
                    cctvData.fetchCCTVData(clickedPolyline.getPoints(), new CCTVData.CCTVDataCallback(){
                        @Override
                        public void onCCTVDataReceived(int count) {
                            mapActivity.updateBottomSheetCCTVCount(count);
                        }
                    });

                    // Fetch police station data when a new polyline is clicked
                    policeStationData.fetchPoliceStationData(clickedPolyline.getPoints(), new PoliceStationData.PoliceStationDataCallback(){
                        @Override
                        public void onPoliceStationDataReceived(int count) {
                            mapActivity.updateBottomSheetPoliceStationCount(count);
                        }
                    });

                    // Fetch streetlight data when a new polyline is clicked
                    streetlightData.fetchStreetlightData(clickedPolyline.getPoints(), new StreetlightData.StreetlightDataCallback() {
                        @Override
                        public void onStreetlightDataReceived(int count) {
                            mapActivity.updateBottomSheetStreetlightCount(count);
                        }
                    });
                }
            };
            gMap.setOnPolylineClickListener(polylineClickListener);
        }
    }



    // Modify the resetPolylinesColor method
    void resetPolylinesColor() {
        for (Polyline p : polylines) {
            if (p != primaryPolyline) {
                p.setColor(Color.GRAY);
            }
        }
    }



    // Helper method to zoom to clicked polyline
    private void zoomToPolyline(Polyline polyline) {
        LatLngBounds.Builder builder = new LatLngBounds.Builder();
        List<LatLng> points = polyline.getPoints();
        for (LatLng point : points) {
            builder.include(point);
        }
        LatLngBounds bounds = builder.build();
        gMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 100));
    }



    // Helper method to calculate distance between two LatLng points
    private double computeDistance(LatLng from, LatLng to) {
        // You can use the Haversine formula or the Google Maps Distance Matrix API to compute accurate distances.
        // For simplicity, here's a simple computation of distance between two LatLng points.
        double earthRadius = 6371; // Earth's radius in kilometers
        double dLat = Math.toRadians(to.latitude - from.latitude);
        double dLng = Math.toRadians(to.longitude - from.longitude);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(from.latitude)) * Math.cos(Math.toRadians(to.latitude)) *
                        Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return earthRadius * c;
    }



    // Helper method to compute distance of a polyline (you can use a more accurate method)
    private double computePolylineDistance(List<LatLng> polyline) {
        double distance = 0;
        for (int i = 0; i < polyline.size() - 1; i++) {
            distance += computeDistance(polyline.get(i), polyline.get(i + 1));
        }
        return distance;
    }



    // Decode polyline string to list of LatLng points
    private List<LatLng> decodePolyline(String encoded) {
        List<LatLng> poly = new ArrayList<>();
        int index = 0, len = encoded.length();
        int lat = 0, lng = 0;
        while (index < len) {
            int b, shift = 0, result = 0;
            do {
                b = encoded.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20);
            int dlat = ((result & 1) != 0 ? ~(result >> 1) : (result >> 1));
            lat += dlat;
            shift = 0;
            result = 0;
            do {
                b = encoded.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20);
            int dlng = ((result & 1) != 0 ? ~(result >> 1) : (result >> 1));
            lng += dlng;
            LatLng p = new LatLng((((double) lat / 1E5)),
                    (((double) lng / 1E5)));
            poly.add(p);
        }
        return poly;
    }
}

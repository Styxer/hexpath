package dev.hexpath.geo.places;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Wire format of a place. GeoJSON coordinates are [lon, lat]. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlaceJson(String id, String name, String nameHe, String nameEn, String category, GeoPoint location,
                        Double score, Double distanceM) {

    public record GeoPoint(String type, List<Double> coordinates) {
        public static GeoPoint of(double lat, double lon) {
            return new GeoPoint("Point", List.of(lon, lat));
        }
    }

    static PlaceJson of(Place p, Double score, Double distanceM) {
        return new PlaceJson(p.id(), p.name(), p.nameHe(), p.nameEn(), p.category(), GeoPoint.of(p.lat(), p.lon()),
                score, distanceM);
    }
}

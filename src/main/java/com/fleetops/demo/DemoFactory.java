package com.fleetops.demo;

import java.time.Instant;
import java.util.List;
import java.util.Random;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.fleetops.fleet.Warehouse;
import com.fleetops.shipment.Shipment;
import com.fleetops.shipment.ShipmentRepository;
import com.fleetops.shipment.ShipmentService;

/** Creates synthetic shipments for the demo: invented businesses on real Punjab town streets. */
@Component
@Profile("demo")
public class DemoFactory {

    private final ShipmentRepository shipments;
    private final ShipmentService shipmentService;
    private final Random random = new Random();

    public DemoFactory(ShipmentRepository shipments, ShipmentService shipmentService) {
        this.shipments = shipments;
        this.shipmentService = shipmentService;
    }

    public List<DemoData.TownSeed> townsOf(String warehouseCode) {
        return DemoData.TOWNS.stream().filter(t -> t.warehouseCode().equals(warehouseCode)).toList();
    }

    public DemoData.TownSeed town(String name) {
        return DemoData.TOWNS.stream().filter(t -> t.town().equals(name)).findFirst().orElseThrow();
    }

    public Shipment shipment(Warehouse origin, DemoData.TownSeed town, Instant deadline, Instant createdAt, int weightKg) {
        String business = pick(DemoData.BUSINESS_PREFIXES) + " " + pick(DemoData.BUSINESS_KINDS);
        String street = pick(DemoData.STREETS);
        // Each named street in a town is one delivery point, so several shipments can share a stop.
        double jitterLat = ((street.hashCode() & 0xff) - 128) / 128.0 * 0.006;
        double jitterLng = (((street.hashCode() >> 8) & 0xff) - 128) / 128.0 * 0.006;
        String recipient = pick(DemoData.DRIVER_NAMES).split(" ")[0] + " " + pick(DemoData.BUSINESS_PREFIXES);
        Shipment s = new Shipment(shipmentService.nextTrackingNo(), origin, recipient,
            "+91 98" + String.format("%08d", random.nextInt(99_999_999)),
            town.town() + " — " + business, street + ", " + town.town() + ", Punjab " + town.pin(), town.pin(),
            town.lat() + jitterLat, town.lng() + jitterLng, weightKg, deadline, createdAt);
        return shipments.save(s);
    }

    public int weight() {
        return 40 + random.nextInt(260);
    }

    public Random random() {
        return random;
    }

    private <T> T pick(List<T> list) {
        return list.get(random.nextInt(list.size()));
    }
}

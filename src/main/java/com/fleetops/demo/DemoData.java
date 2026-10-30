package com.fleetops.demo;

import java.util.List;

/**
 * Synthetic Punjab reference data for the demo. Town coordinates and PIN codes are approximate
 * public facts; every business, person, vehicle and shipment is invented.
 */
final class DemoData {

    private DemoData() {
    }

    record WarehouseSeed(String code, String name, String city, String address, double lat, double lng, String regPrefix) {
    }

    record TownSeed(String warehouseCode, String town, String pin, double lat, double lng) {
    }

    record VehicleType(String name, int capacityKg) {
    }

    static final List<WarehouseSeed> WAREHOUSES = List.of(
        new WarehouseSeed("LDH", "Ludhiana DC", "Ludhiana", "Focal Point Phase VIII, Ludhiana", 30.8786, 75.9351, "PB 10"),
        new WarehouseSeed("JUC", "Jalandhar DC", "Jalandhar", "Leather Complex Road, Jalandhar", 31.3462, 75.5466, "PB 08"),
        new WarehouseSeed("ASR", "Amritsar Hub", "Amritsar", "GT Road, Verka Bypass, Amritsar", 31.6418, 74.9215, "PB 02"),
        new WarehouseSeed("PTA", "Patiala Depot", "Patiala", "Sirhind Road Industrial Area, Patiala", 30.3560, 76.3780, "PB 11"),
        new WarehouseSeed("BTI", "Bathinda Depot", "Bathinda", "Mansa Road Growth Centre, Bathinda", 30.1890, 74.9800, "PB 03"),
        new WarehouseSeed("MOH", "Mohali Hub", "Mohali", "Industrial Area Phase 8B, Mohali", 30.7150, 76.6960, "PB 65"));

    static final List<TownSeed> TOWNS = List.of(
        new TownSeed("LDH", "Khanna", "141401", 30.7051, 76.2219),
        new TownSeed("LDH", "Doraha", "141421", 30.7990, 76.0250),
        new TownSeed("LDH", "Jagraon", "142026", 30.7883, 75.4732),
        new TownSeed("LDH", "Moga", "142001", 30.8165, 75.1717),
        new TownSeed("LDH", "Samrala", "141114", 30.8360, 76.1930),
        new TownSeed("LDH", "Raikot", "141109", 30.6500, 75.6000),
        new TownSeed("LDH", "Mullanpur Dakha", "141101", 30.8460, 75.6900),
        new TownSeed("LDH", "Payal", "141416", 30.6690, 76.0690),
        new TownSeed("JUC", "Phagwara", "144401", 31.2240, 75.7708),
        new TownSeed("JUC", "Kapurthala", "144601", 31.3800, 75.3800),
        new TownSeed("JUC", "Hoshiarpur", "146001", 31.5320, 75.9110),
        new TownSeed("JUC", "Nakodar", "144040", 31.1250, 75.4750),
        new TownSeed("JUC", "Adampur", "144102", 31.4320, 75.7150),
        new TownSeed("JUC", "Kartarpur", "144801", 31.4410, 75.4980),
        new TownSeed("JUC", "Nawanshahr", "144514", 31.1250, 76.1160),
        new TownSeed("ASR", "Tarn Taran", "143401", 31.4510, 74.9270),
        new TownSeed("ASR", "Batala", "143505", 31.8190, 75.2020),
        new TownSeed("ASR", "Gurdaspur", "143521", 32.0410, 75.4060),
        new TownSeed("ASR", "Ajnala", "143102", 31.8440, 74.7600),
        new TownSeed("ASR", "Jandiala Guru", "143115", 31.5600, 75.0280),
        new TownSeed("ASR", "Rayya", "143112", 31.5310, 75.2420),
        new TownSeed("PTA", "Rajpura", "140401", 30.4840, 76.5940),
        new TownSeed("PTA", "Nabha", "147201", 30.3740, 76.1470),
        new TownSeed("PTA", "Samana", "147101", 30.1550, 76.1900),
        new TownSeed("PTA", "Sangrur", "148001", 30.2450, 75.8420),
        new TownSeed("PTA", "Sirhind", "140406", 30.6430, 76.3840),
        new TownSeed("BTI", "Mansa", "151505", 29.9990, 75.3930),
        new TownSeed("BTI", "Rampura Phul", "151103", 30.2700, 75.2400),
        new TownSeed("BTI", "Faridkot", "151203", 30.6750, 74.7550),
        new TownSeed("BTI", "Muktsar", "152026", 30.4740, 74.5160),
        new TownSeed("BTI", "Kotkapura", "151204", 30.5830, 74.8330),
        new TownSeed("BTI", "Malout", "152107", 30.1900, 74.4990),
        new TownSeed("MOH", "Kharar", "140301", 30.7460, 76.6450),
        new TownSeed("MOH", "Zirakpur", "140603", 30.6430, 76.8170),
        new TownSeed("MOH", "Ropar", "140001", 30.9660, 76.5330),
        new TownSeed("MOH", "Banur", "140601", 30.5550, 76.7190),
        new TownSeed("MOH", "Dera Bassi", "140507", 30.5870, 76.8430),
        new TownSeed("MOH", "Kurali", "140103", 30.8310, 76.5760));

    static final List<VehicleType> VEHICLE_TYPES = List.of(
        new VehicleType("Tata 407 light truck", 2500),
        new VehicleType("Ashok Leyland Dost", 1500),
        new VehicleType("Eicher Pro 2049", 3500),
        new VehicleType("Mahindra Bolero Pik-Up", 1200));

    static final List<String> DRIVER_NAMES = List.of(
        "Gurpreet Singh", "Harjit Kaur", "Manpreet Gill", "Amandeep Sandhu", "Baljinder Singh", "Kulwant Brar",
        "Sukhwinder Dhillon", "Jaswinder Kaur", "Paramjit Grewal", "Rajvir Sidhu", "Harbhajan Mann", "Navdeep Cheema",
        "Tejinder Bains", "Satnam Virk", "Lakhvir Aulakh", "Iqbal Singh", "Ravinder Kumar", "Sandeep Sharma",
        "Mohit Arora", "Rakesh Verma", "Bikramjit Randhawa", "Kamaljit Kaur", "Daljit Bhullar", "Gagandeep Toor");

    static final List<String> BUSINESS_PREFIXES = List.of(
        "Sandhu", "Gill", "Dhillon", "Brar", "Grewal", "Sidhu", "Bajwa", "Arora", "Malhotra", "Bansal",
        "Garg", "Mittal", "Kapoor", "Chawla", "Sethi", "Aggarwal", "Jindal", "Goyal");

    static final List<String> BUSINESS_KINDS = List.of(
        "Agro Traders", "Kiryana Store", "Hardware", "Medicos", "Electricals", "Cloth House", "Auto Parts",
        "Sweets", "Dairy", "Pharma Distributors", "Steel Works", "Seeds & Fertilisers", "Mobile Point", "Furnishers");

    static final List<String> STREETS = List.of(
        "Main Bazaar", "GT Road", "Railway Road", "Civil Lines", "Grain Market", "Bus Stand Road", "Model Town",
        "Mall Road", "Court Road", "Old Town", "Industrial Area", "Sadar Bazaar");

    static final List<String> FAILURE_REASONS = List.of(
        "Recipient not available", "Shop closed", "Address not found", "Refused by recipient",
        "Payment not ready", "Access road blocked");
}

package com.bunshock.note_app_for_it_frontend.models;

import java.time.LocalDate;
import java.util.List;

public class HistoryFilter {
    private LocalDate   fromDate;
    private LocalDate   toDate;
    private List<String> profileTypes;  // null = all
    private List<String> glpiStatuses;  // null = all; values: PENDING, SYNCED, REJECTED, N_A
    private String       recipientSearch;
    private String       authorSearch;
    private List<String> itemTypes;     // null = all
    private List<String> itemBrands;    // null = all
    private List<String> itemModels;    // null = all
    private List<String> sedes;         // null = all
    private List<String> approvalStatuses; // null = all; values: PENDING, APPROVED, RECHAZADO

    public HistoryFilter() {}

    // A not-yet-approved note's GLPI sync isn't real yet (an admin might still reject the whole
    // note), and a rejected note's sync never should be — so only an APPROVED note's pending GLPI
    // items count as genuinely "pending sync." Direct user requirement: "it only counts for
    // pending GLPI/return if the note is approved."
    public static HistoryFilter pendingGlpiSync() {
        HistoryFilter f = new HistoryFilter();
        f.glpiStatuses = List.of("PENDING");
        f.approvalStatuses = List.of("APPROVED");
        return f;
    }

    public static HistoryFilter pendingApproval() {
        HistoryFilter f = new HistoryFilter();
        f.approvalStatuses = List.of("PENDING");
        return f;
    }

    public LocalDate   getFromDate()       { return fromDate; }
    public void        setFromDate(LocalDate v)        { fromDate = v; }

    public LocalDate   getToDate()         { return toDate; }
    public void        setToDate(LocalDate v)          { toDate = v; }

    public List<String> getProfileTypes()  { return profileTypes; }
    public void         setProfileTypes(List<String> v){ profileTypes = v; }

    public List<String> getGlpiStatuses()  { return glpiStatuses; }
    public void         setGlpiStatuses(List<String> v){ glpiStatuses = v; }

    public String       getRecipientSearch(){ return recipientSearch; }
    public void         setRecipientSearch(String v)  { recipientSearch = v; }

    public String       getAuthorSearch()  { return authorSearch; }
    public void         setAuthorSearch(String v)     { authorSearch = v; }

    public List<String> getItemTypes()     { return itemTypes; }
    public void         setItemTypes(List<String> v)  { itemTypes = v; }

    public List<String> getItemBrands()    { return itemBrands; }
    public void         setItemBrands(List<String> v) { itemBrands = v; }

    public List<String> getItemModels()    { return itemModels; }
    public void         setItemModels(List<String> v) { itemModels = v; }

    public List<String> getSedes()         { return sedes; }
    public void         setSedes(List<String> v)      { sedes = v; }

    public List<String> getApprovalStatuses() { return approvalStatuses; }
    public void         setApprovalStatuses(List<String> v) { approvalStatuses = v; }
}

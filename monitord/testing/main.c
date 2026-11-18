#include <windows.h>
#include <pdh.h>
#include <stdio.h>

#pragma comment(lib, "pdh.lib")

int main(void) {
    PDH_HQUERY q; PDH_HCOUNTER c;
    PdhOpenQueryW(NULL, 0, &q);
    PdhAddEnglishCounterW(q, L"\\Processor(_Total)\\% Processor Time", 0, &c);
    PdhCollectQueryData(q);                 /* baseline: no valid rate yet */
    for (int i=0;i<40;i++) {
        Sleep(1000);
        PdhCollectQueryData(q);
        PDH_FMT_COUNTERVALUE v;
        if (PdhGetFormattedCounterValue(c, PDH_FMT_DOUBLE, NULL, &v) == ERROR_SUCCESS)
            printf("cpu %.1f%%\n", v.doubleValue);
    }
    return 0;
}
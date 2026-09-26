# ML-контур

Код обучения и инференса ведёт ML-команда в каталоге [`mostrans_handoff/mostrans/`](../mostrans_handoff/mostrans/)
(так он передаётся между Colab и репозиторием). Этот каталог — точка входа по структуре проекта.

| Что | Где |
|---|---|
| Инструкция запуска, параметры, чекпоинты | [`ML_README.md`](../mostrans_handoff/mostrans/ML_README.md) |
| Ноутбук обучения и инференса (Colab) | [`baseline_colab.ipynb`](../mostrans_handoff/mostrans/baseline_colab.ipynb), исходник — [`tools/build_nb.py`](../mostrans_handoff/mostrans/tools/build_nb.py) |
| **Прогнанный ноутбук финального прогона** (код тот же, с выводами: CV по фолдам, ablation источников, тест на утечку, потоковый бэктест) | [`notebooks_extended/baseline_colab (10).ipynb`](../mostrans_handoff/mostrans/notebooks_extended/) — прогон `20260926_1142`, CV 0.8921, **лидерборд 0.89234** |
| Пайплайн приёма сырых валидаций (DuckDB, сверка с labels) | [`pipeline/ingest_raw.py`](../mostrans_handoff/mostrans/pipeline/ingest_raw.py) |
| Внешние данные и скрипты их получения | [`external_data/`](../mostrans_handoff/mostrans/external_data/), описание — [`EXTERNAL_DATA.md`](../mostrans_handoff/mostrans/EXTERNAL_DATA.md) |
| Артефакты прогноза для сервиса | [`data/forecast/`](../data/forecast/): `forecast_hourly.csv`, `forecast_year_monthly.csv`, `coefficients.json`, `ml_contract.json` |
| Веса моделей LightGBM (9 шт.: горизонты 3/14/28 × seed 42/43/44) | [`artifacts/`](artifacts/) — `lgbm_h{3,14,28}_s{42,43,44}.txt` прогона `20260926_1142` (по 500 деревьев, L1, 16 признаков по `ml_contract.json`); папка — копия `MyDrive/mostrans/submissions/artifacts/` целиком, прогнозы в ней идентичны `data/forecast/` |
| Сабмиты | [`submissions/`](../mostrans_handoff/mostrans/submissions/), [`data/forecast/`](../data/forecast/) |

Загрузить модели вне ноутбука:

```python
import json, lightgbm as lgb, pandas as pd
c = json.load(open("ml/artifacts/ml_contract.json", encoding="utf-8"))
models = [lgb.Booster(model_file=f"ml/artifacts/{m}") for m in c["models"]]
# X — признаки c["features"] в точке прогноза, route — categorical c["categorical"]["route"];
# прогноз ML = профиль × clip(среднее 9 моделей, 0.3, 2.0), итог = 0.2·профиль + 0.8·ML (см. c["target"], c["blend"])
```

Сервис модель не вызывает: прогноз предрассчитан ноутбуком и импортируется как прогон
(`tools/sync_ml_artifacts.sh <service_artifacts.zip>` → `docker compose restart backend`). Архив создаётся ноутбуком вне Git; в репозитории уже лежат распакованные финальные артефакты.
Контракт между контурами — `ml_contract.json` и формат `route;date;hour;pred;model_version`.


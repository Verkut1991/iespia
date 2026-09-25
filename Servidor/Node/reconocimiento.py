#!/usr/bin/env python3
# Importación de librerías necesarias
import sys          # Para leer argumentos de la línea de comandos (sys.argv)
import os           # Para operaciones del sistema operativo (rutas, archivos)
import numpy as np  # Para cálculos numéricos y manejo de matrices (arrays)
import json         # Para formatear la salida como texto JSON
from PIL import Image # Para cargar y manipular imágenes
from pymongo import MongoClient # Cliente nativo de MongoDB
import torch        # PyTorch: Librería base para Deep Learning
from torch import tensor # Para convertir datos en tensores de PyTorch
from openfacekit import FaceRecognizer, ReferenceEmbeddings # Librería específica de reconocimiento facial

# Función principal que realiza todo el proceso de reconocimiento
def perform_recognition(image_path):
    try:
        # 1. Cargar la Imagen
        # Se imprime un mensaje de depuración (debug) indicando qué archivo se va a cargar.
        print(f"--> Cargando imagen: {image_path}")
        # Abre la imagen desde la ruta especificada y la convierte al modo RGB (Red, Green, Blue).
        # Esto es necesario porque algunas imágenes pueden venir en otros formatos (CMYK, RGBA, etc.).
        image = Image.open(image_path).convert('RGB')

        # 2. Inicializar el Detector
        # Se crea una instancia del reconocedor facial con parámetros específicos.
        face_recognizer = FaceRecognizer(
            detector                = "MTCNN", # Algoritmo usado para encontrar las caras en la foto.
            encoder                 = None,    # Encoder predeterminado.
            min_face_size           = 20,      # Tamaño mínimo de cara a detectar (en píxeles).
            thresholds              = [0.6, 0.7, 0.7],  # Umbrales de confianza para el detector MTCNN.
            min_confidence_detector = 0.5,     # Confianza mínima para considerar que algo es una cara.
            similarity_threshold    = 0.5,     # Umbral de similitud para considerar dos caras iguales.
            similarity_metric       = "cosine", # Métrica matemática usada para comparar (Similitud del Coseno).
            keep_all                = True,    # Mantiene todas las caras detectadas, no solo la más grande.
            verbose                 = False    # Desactiva logs internos excesivos de la librería.
        )

        # 3. Extraer Caras
        print("--> Extrayendo caras...")
        # Llama al método para recortar las caras de la imagen cargada.
        result = face_recognizer.extract_faces(image=image)
        
        # Si no devuelve nada, significa que no encontró caras. Retorna estado "no_faces".
        if result is None:
            return {"status": "no_faces"}

        # Manejo del resultado: La librería puede devolver una tupla (caras, probabilidades) o solo las caras.
        # Aquí normalizamos para obtener siempre la lista de caras.
        faces = result[0] if isinstance(result, tuple) else result
        
        # Verificación doble: Si la lista de caras está vacía o es None.
        if faces is None or (hasattr(faces, "__len__") and len(faces) == 0):
            return {"status": "no_faces"}

        # 4. Calcular Embeddings (Huellas digitales faciales)
        print("--> Calculando embeddings...")
        # Tomamos la primera cara detectada (para simplificar en este escaneo de una sola persona).
        # Si quisieramos procesar todas, haríamos un bucle aquí.
        face_to_process = faces[0:1] if hasattr(faces, "__getitem__") else [faces]
        
        # Convertimos la imagen de la cara en una lista de números (vector/embedding) que la representa matemáticamente.
        embeddings = face_recognizer.calculate_embeddings(face_images=face_to_process)
        
        # Si no se pudo generar el embedding, retornamos error.
        if embeddings is None or (hasattr(embeddings, "__len__") and len(embeddings) == 0):
            return {"status": "error", "message": "No se generaron embeddings"}

        # Extraemos el embedding concreto de la lista.
        scan_embedding = embeddings[0] if hasattr(embeddings, "__getitem__") else embeddings

        # 5. Conectar a MongoDB y recuperar referencias
        print("--> Conectando a MongoDB...")
        client = MongoClient(os.environ.get("MONGODB_URI", "mongodb://localhost:27017"))
        db = client[os.environ.get("MONGODB_DB", "iespia_demo")]
        coleccion = db["tensores"]  # Selecciona la colección donde están guardadas las caras conocidas.

        # Descarga TODOS los objetivos (targets) de la base de datos.
        all_targets = list(coleccion.find())
        
        # Si la base de datos está vacía, no hay con quién comparar.
        if not all_targets:
            return {"status": "no_targets_in_db"}

        # 6. Comparar el embedding del escaneo con la base de datos
        print(f"--> Comparando con {len(all_targets)} objetivos...")
        matches = [] # Lista para guardar las coincidencias encontradas.
        
        # Recorre cada objetivo descargado de la base de datos.
        for target in all_targets:
            try:
                # Recupera el embedding de referencia guardado (campo "data") y lo convierte a array de Numpy.
                ref_data = np.array(target["data"])
                # Lo convierte a un Tensor de PyTorch (tipo float32) para poder operar.
                ref_tensor = tensor(ref_data, dtype=torch.float32)
                
                # Aplana los vectores (hacerlos de 1 dimensión) para asegurar compatibilidad.
                a = scan_embedding.flatten()
                b = ref_tensor.flatten()
                
                # Verifica que ambos vectores tengan el mismo tamaño (dimensión).
                if a.shape == b.shape:
                    # Calcula la Similitud del Coseno entre los dos vectores.
                    # unsqueeze(0) añade una dimensión extra porque la función espera lotes (batches).
                    # .item() extrae el valor numérico escalar del tensor resultante.
                    cos_sim = torch.nn.functional.cosine_similarity(a.unsqueeze(0), b.unsqueeze(0)).item()
                    
                    # Si la similitud supera el 50% (0.5), se considera una coincidencia.
                    if cos_sim > 0.5: 
                        matches.append({
                            "id": str(target["_id"]),   # ID del documento en MongoDB.
                            "name": target["nombre"],   # Nombre de la persona.
                            "confidence": cos_sim * 100 # Convierte a porcentaje (0-100).
                        })
            except Exception as inner_e:
                # Si falla la comparación con un objetivo concreto, lo saltamos y seguimos con el siguiente.
                print(f"--> saltando objetivo {target.get('nombre')}: {str(inner_e)}")

        # Si se encontraron coincidencias después de revisar todos:
        if matches:
            # Ordena las coincidencias por confianza (confidence) de mayor a menor.
            matches.sort(key=lambda x: x["confidence"], reverse=True)
            # Retorna éxito y la lista de coincidencias.
            return {"status": "match", "matches": matches}
        else:
            # Si terminó el bucle y no hay coincidencias.
            return {"status": "no_match"}

    except Exception as e:
        # Captura cualquier error global (ej: archivo no encontrado, error de red).
        import traceback
        traceback.print_exc() # Imprime la traza completa del error en consola (stderr).
        return {"status": "error", "message": str(e)}

# Bloque principal de ejecución
if __name__ == "__main__":
    # Verifica si se pasó el argumento de la ruta de la imagen.
    if len(sys.argv) < 2:
        # Si falta el argumento, imprime un JSON de error y sale y termina.
        print(json.dumps({"status": "error", "message": "Uso: python perform_recognition.py <ruta_imagen>"}))
        sys.exit(1)
    
    # Obtiene la ruta de la imagen del primer argumento.
    img_path = sys.argv[1]
    
    # Llama a la función principal.
    result = perform_recognition(img_path)
    
    # Imprime el resultado final como un string JSON por salida estándar (stdout).
    # Esto es lo que lee Node.js.
    print(json.dumps(result))

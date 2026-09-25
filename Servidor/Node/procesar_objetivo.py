#!/usr/bin/env python3
import sys          # Para leer argumentos de entrada.
import os           # Para funciones del sistema operativo.
import numpy as np  # Para manejo de arrays eficientes.
from pymongo import MongoClient # Cliente de base de datos MongoDB.
from torch import torch, tensor # Librería de aprendizaje profundo PyTorch.
from PIL import Image # Librería de procesamiento de imágenes (Pillow).

# Modelos de Deep Learning y reconocimiento facial
from openfacekit import FaceRecognizer # Librería que encapsula la detección y reconocimiento.

# Función principal para procesar un nuevo objetivo (persona a vigilar).
# Recibe la ruta de la imagen y el nombre de la persona.
def process_target(image_path, name):
    print(f"Procesando objetivo: {name} con imagen: {image_path}")
    
    try:
        # 1. Cargar la Imagen
        # Abre la imagen desde el disco y fuerza la conversión a RGB para evitar errores de formato.
        image = Image.open(image_path).convert('RGB')

        # 2. Inicializar el Detector
        # Configura el motor de reconocimiento facial.
        face_recognizer = FaceRecognizer(
            detector                = "MTCNN", # Usa MTCNN para encontrar caras (es preciso).
            encoder                 = None,    # Encoder por defecto de la librería.
            min_face_size           = 20,      # Ignora caras menores a 20x20 píxeles.
            thresholds              = [0.6, 0.7, 0.7],  # Umbrales de sensibilidad.
            min_confidence_detector = 0.5,     # Confianza mínima del 50%.
            similarity_threshold    = 0.5,     # Similitud mínima.
            similarity_metric       = "cosine", # Usa distancia coseno.
            keep_all                = True,    # Mantiene todas las caras candidatas.
            verbose                 = False    # Silencioso en consola.
        )

        # 3. Extraer caras
        # Recorta las caras encontradas en la imagen.
        result = face_recognizer.extract_faces(image=image)
        
        # Validación: Si no hay resultado.
        if result is None:
            print("Error: No se detectó ninguna cara en la imagen")
            return

        # Manejo de formatos de retorno de la librería (puede devolver tupla o lista).
        faces = result[0] if isinstance(result, tuple) else result
        
        # Validación: Si la lista de caras está vacía.
        if faces is None or (hasattr(faces, "__len__") and len(faces) == 0):
            print("Error: No se detectó ninguna cara en la imagen")
            return

        # 4. Calcular Embeddings (el mapa numérico de la cara)
        # Tomamos solo la primera cara encontrada (asumimos que la foto del objetivo es individual).
        face_to_process = faces[0:1] if hasattr(faces, "__getitem__") else [faces]
        
        # Genera el vector numérico único que identifica esa cara.
        embeddings = face_recognizer.calculate_embeddings(face_images=face_to_process)
        
        # Validación: Si falló la generación del embedding.
        if embeddings is None or (hasattr(embeddings, "__len__") and len(embeddings) == 0):
            print("Error: No se generaron embeddings")
            return

        # Extraemos el tensor del embedding.
        embedding_tensor = embeddings[0] if hasattr(embeddings, "__getitem__") else embeddings

        # 5. Conectar a MongoDB
        client = MongoClient(os.environ.get("MONGODB_URI", "mongodb://localhost:27017"))
        db = client[os.environ.get("MONGODB_DB", "iespia_demo")]
        coleccion = db["tensores"]  # Colección de huellas faciales.

        # 6. Guardar en MongoDB
        # Prepara el documento JSON para guardar.
        datos = {
            "nombre": name,
            "data": embedding_tensor.numpy().tolist(), # Convierte el tensor a lista de Python estándar.
            "shape": list(embedding_tensor.shape),     # Guarda las dimensiones de la matriz.
            "dtype": str(embedding_tensor.dtype),      # Guarda el tipo de dato (float32).
            "image_path": image_path                   # Referencia al archivo original.
        }
        
        # Inserta o actualiza (upsert=True) el documento buscando por nombre.
        coleccion.update_one({"nombre": name}, {"$set": datos}, upsert=True)
        print(f"✓ Objetivo '{name}' procesado y guardado en MongoDB")

    except Exception as e:
        # Bloque de recuperación ante fallos (ej. reconexión a DB).
        print(f"Error procesando objetivo (intento recuperación): {str(e)}")
        
        # Intenta reconectar a MongoDB.
        client = MongoClient(os.environ.get("MONGODB_URI", "mongodb://localhost:27017"))
        db = client[os.environ.get("MONGODB_DB", "iespia_demo")]
        coleccion = db["tensores"]

        # Intenta guardar nuevamente los datos (asumiendo que embedding_tensor se calculó bien).
        try:
            datos = {
                "nombre": name,
                "data": embedding_tensor.numpy().tolist(),
                "shape": list(embedding_tensor.shape),
                "dtype": str(embedding_tensor.dtype),
                "image_path": image_path
            }
            
            # Actualiza o inserta de nuevo.
            coleccion.update_one({"nombre": name}, {"$set": datos}, upsert=True)
            print(f"✓ Objetivo '{name}' procesado y guardado en MongoDB (tras recuperación)")
        except Exception as retry_e:
             print(f"Error final: {str(retry_e)}")

# Punto de entrada del script.
if __name__ == "__main__":
    # Verifica que se reciban los 2 argumentos necesarios: ruta y nombre.
    if len(sys.argv) < 3:
        print("Uso: python process_target.py <ruta_imagen> <nombre_objetivo>")
        sys.exit(1)
    
    # Lee los argumentos.
    img_path = sys.argv[1]
    target_name = sys.argv[2]
    
    # Ejecuta la función principal.
    process_target(img_path, target_name)

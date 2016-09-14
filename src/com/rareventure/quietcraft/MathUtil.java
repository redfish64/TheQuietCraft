package com.rareventure.quietcraft;

import java.util.Random;

/**
 * Created by tim on 9/12/16.
 */
public class MathUtil {
    /**
     * The normal distribution with a min and max cut off as an int.
     */
    public static int normalRandomInt(RandomNormalParams params) {
        double val = normalRandom(params);
        int iv = (int)(Math.round(val));
        if(iv < params.min)
            iv = (int)Math.ceil(params.min);
        else if(iv > params.max)
            iv = (int)Math.floor(params.max);
        return iv;
    }

    public static Random random = new Random();

    /**
     * The normal (bell curve) distribution with a min and max.
     */
    private static double normalRandom(RandomNormalParams params) {
        synchronized (random)
        {
            double v = random.nextGaussian() * params.std + params.mean;

            return Math.max(Math.min(v,params.max),params.min);
        }
    }

    public static class RandomNormalParams {
        public double mean,std,min,max;

        public RandomNormalParams(double mean, double std, double min, double max) {
            this.mean = mean;
            this.std = std;
            this.min = min;
            this.max = max;
        }
    }

    public static void main(String []argv)
    {
        RandomNormalParams r = new RandomNormalParams(10,3,7,15);
        for(int i = 0; i < 20; i++)
        {
            for(int j = 0; j < 5; j++)
            {
                System.out.print(String.format("%8.3f ",normalRandom(r)));
            }
            System.out.println();
        }
        System.out.println();
        for(int i = 0; i < 20; i++)
        {
            for(int j = 0; j < 5; j++)
            {
                System.out.print(String.format("%8d ",normalRandomInt(r)));
            }
            System.out.println();
        }
    }
}